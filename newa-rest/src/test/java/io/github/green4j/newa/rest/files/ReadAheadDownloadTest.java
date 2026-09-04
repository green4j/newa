/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


package io.github.green4j.newa.rest.files;

import io.github.green4j.newa.server.NettyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real download off a real socket, with the file read by a pool rather than by the loop which writes it.
 * <p>
 * A compressor in front of the file handler is what puts the response on that path - a region reaches neither
 * a compressor nor TLS - and the file is large enough that it takes many chunks, which is where a transfer
 * that is resumed one chunk at a time either works or stops halfway.
 */
class ReadAheadDownloadTest {
    private static final String HOST = "127.0.0.1";
    private static final int SIZE = 4 * 1024 * 1024;
    private static final String READER_PREFIX = "file-reads-";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final AtomicInteger reads = new AtomicInteger();
    private final Set<String> readers = ConcurrentHashMap.newKeySet();

    private ExecutorService pool;
    private NettyServer server;
    private byte[] content;

    @TempDir
    private Path filesRoot;

    @BeforeEach
    public void setUp() throws IOException {
        content = new byte[SIZE];
        new Random(42).nextBytes(content);
        Files.write(filesRoot.resolve("big.bin"), content);

        final AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2,
                work -> new Thread(work, READER_PREFIX + threads.incrementAndGet()));
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        if (server != null) {
            server.close();
            server = null;
        }
        if (pool != null) {
            pool.shutdown();
            Assertions.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS),
                    "a read was still running with the server gone");
            pool = null;
        }
    }

    private void startServing() throws Exception {
        server = FileServer.of(FileSet.builder().serve("/files", filesRoot).build())
                .withCompression() // which is what takes sendfile(2) away and puts this on the read path
                .withReadExecutor(work -> {
                    reads.incrementAndGet();
                    pool.execute(() -> {
                        readers.add(Thread.currentThread().getName());
                        work.run();
                    });
                })
                .start(0);
    }

    private HttpResponse<byte[]> get(final String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + server.port() + path)).build(),
                BodyHandlers.ofByteArray());
    }

    @Test
    public void aFileReadSomewhereElseArrivesWhole() throws Exception {
        startServing();

        final HttpResponse<byte[]> response = get("/files/big.bin");

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertEquals(String.valueOf(SIZE), response.headers().firstValue("Content-Length").orElse(null));
        Assertions.assertArrayEquals(content, response.body());

        Assertions.assertTrue(reads.get() > 1, "a file this size is more than one read, or it was not read here");
        for (final String reader : readers) {
            Assertions.assertTrue(reader.startsWith(READER_PREFIX),
                    "the file was read by " + reader + ", which is not one of the threads it was given");
        }
    }

    @Test
    public void andTheConnectionIsGoodForTheNextOne() throws Exception {
        // the framing of a response pumped a chunk at a time: a keep-alive connection carrying a second
        // request is the only thing which can tell whether the first one ended where it said it would
        startServing();

        Assertions.assertArrayEquals(content, get("/files/big.bin").body());
        Assertions.assertArrayEquals(content, get("/files/big.bin").body());
    }

    @Test
    public void andARangeIsTheRangeItAsksFor() throws Exception {
        startServing();

        final HttpResponse<byte[]> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + server.port() + "/files/big.bin"))
                        .header("Range", "bytes=1000-1000999")
                        .build(),
                BodyHandlers.ofByteArray());

        final byte[] expected = new byte[1000000];
        System.arraycopy(content, 1000, expected, 0, expected.length);

        Assertions.assertEquals(206, response.statusCode());
        Assertions.assertArrayEquals(expected, response.body());
    }
}
