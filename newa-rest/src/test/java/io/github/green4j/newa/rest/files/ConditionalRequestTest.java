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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * What a peer which already holds a copy of a file is answered with: the validators it is given, the
 * conditions it sends back, and the one header which says the type it was told is the type it gets.
 */
class ConditionalRequestTest {
    private static final String HOST = "127.0.0.1";
    private static final String BODY = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String PATH = "/files/thing.txt";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private NettyServer server;

    @TempDir
    private Path filesRoot;

    @BeforeEach
    public void setUp() throws Exception {
        Files.write(filesRoot.resolve("thing.txt"), BODY.getBytes(StandardCharsets.UTF_8));
        server = FileServer.start(0, FileSet.builder().serve("/files", filesRoot).build());
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    public void aFileIsAnsweredWithSomethingToAskAbout() throws Exception {
        final HttpResponse<byte[]> response = get(PATH);

        Assertions.assertEquals(200, response.statusCode());
        Assertions.assertNotNull(etagOf(response), "no validator to send back");
        Assertions.assertNotNull(headerOf(response, "Last-Modified"));
        Assertions.assertEquals("nosniff", headerOf(response, "x-content-type-options"));
    }

    @Test
    public void theSameFileAskedForAgainIsNotSentAgain() throws Exception {
        final String etag = etagOf(get(PATH));

        final HttpResponse<byte[]> again = get(PATH, "If-None-Match", etag);

        Assertions.assertEquals(304, again.statusCode());
        Assertions.assertEquals(0, again.body().length);
        // the copy being refreshed keeps being compared against something: a 304 carries the validators of
        // the response it stands in for
        Assertions.assertEquals(etag, etagOf(again));
        Assertions.assertNotNull(headerOf(again, "Last-Modified"));
        Assertions.assertEquals("nosniff", headerOf(again, "x-content-type-options"));
    }

    @Test
    public void aWeakTagAsksTheSameQuestion() throws Exception {
        final String etag = etagOf(get(PATH));

        Assertions.assertEquals(304, get(PATH, "If-None-Match", "W/" + etag).statusCode());
    }

    @Test
    public void aStarAsksWhetherThereIsAFileAtAll() throws Exception {
        Assertions.assertEquals(304, get(PATH, "If-None-Match", "*").statusCode());
    }

    @Test
    public void aFileWhichChangedIsSentAgainUnderANewTag() throws Exception {
        final String etag = etagOf(get(PATH));

        rewrite(BODY + " and more");

        final HttpResponse<byte[]> again = get(PATH, "If-None-Match", etag);

        Assertions.assertEquals(200, again.statusCode());
        Assertions.assertNotEquals(etag, etagOf(again));
    }

    @Test
    public void theTagIsAskedFirstAndTheDateIsThenNotAskedAtAll() throws Exception {
        final String stale = etagOf(get(PATH));
        rewrite(BODY + " and more");

        // the date on its own would say "not modified"; the tag says the peer holds the wrong file, and the
        // tag is what decides
        final HttpResponse<byte[]> answered = get(PATH,
                "If-None-Match", stale,
                "If-Modified-Since", headerOf(get(PATH), "Last-Modified"));

        Assertions.assertEquals(200, answered.statusCode());
    }

    @Test
    public void aDateOnItsOwnIsStillAsked() throws Exception {
        final String lastModified = headerOf(get(PATH), "Last-Modified");

        Assertions.assertEquals(304, get(PATH, "If-Modified-Since", lastModified).statusCode());
    }

    @Test
    public void aHeadIsAnsweredTheSameWay() throws Exception {
        final HttpResponse<byte[]> head = send(request(PATH).method("HEAD", HttpRequest.BodyPublishers.noBody()));

        Assertions.assertEquals(200, head.statusCode());
        Assertions.assertEquals("nosniff", headerOf(head, "x-content-type-options"));

        final HttpResponse<byte[]> again = send(request(PATH)
                .header("If-None-Match", etagOf(head))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));

        Assertions.assertEquals(304, again.statusCode());
        Assertions.assertEquals(etagOf(head), etagOf(again));
    }

    @Test
    public void aResumedDownloadOfTheFileThePeerHoldsIsResumed() throws Exception {
        final String etag = etagOf(get(PATH));

        final HttpResponse<byte[]> resumed = get(PATH, "Range", "bytes=10-19", "If-Range", etag);

        Assertions.assertEquals(206, resumed.statusCode());
        Assertions.assertEquals(BODY.substring(10, 20), new String(resumed.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void aResumedDownloadOfOneItNoLongerHoldsStartsAgain() throws Exception {
        // the point of the header: without it those ten bytes are spliced into a file the peer keeps, and
        // what it ends up holding is neither of the two files
        final String etag = etagOf(get(PATH));
        rewrite("completely different content, of a different length");

        final HttpResponse<byte[]> answered = get(PATH, "Range", "bytes=10-19", "If-Range", etag);

        Assertions.assertEquals(200, answered.statusCode());
        Assertions.assertEquals("completely different content, of a different length",
                new String(answered.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void aDateInIfRangeIsAskedTheSameWay() throws Exception {
        final String lastModified = headerOf(get(PATH), "Last-Modified");

        Assertions.assertEquals(206,
                get(PATH, "Range", "bytes=0-4", "If-Range", lastModified).statusCode());
        Assertions.assertEquals(200,
                get(PATH, "Range", "bytes=0-4", "If-Range", "Wed, 21 Oct 2015 07:28:00 GMT").statusCode());
    }

    @Test
    public void aRangeWithoutAConditionIsAnsweredAsItAlwaysWas() throws Exception {
        final HttpResponse<byte[]> ranged = get(PATH, "Range", "bytes=0-4");

        Assertions.assertEquals(206, ranged.statusCode());
        Assertions.assertEquals("01234", new String(ranged.body(), StandardCharsets.UTF_8));
    }

    @Test
    public void everythingThisServerAnswersSaysNotToSniffIt() throws Exception {
        Assertions.assertEquals("nosniff",
                headerOf(get(PATH), "x-content-type-options"), "a file");
        Assertions.assertEquals("nosniff",
                headerOf(get("/files/missing.txt"), "x-content-type-options"), "a 404");
        Assertions.assertEquals("nosniff",
                headerOf(get("/nothing/here"), "x-content-type-options"), "a path no file owns");
        Assertions.assertEquals("nosniff",
                headerOf(get(PATH, "Range", "bytes=9999-"), "x-content-type-options"), "a 416");

        final HttpResponse<byte[]> notAllowed = send(request(PATH)
                .method("DELETE", HttpRequest.BodyPublishers.noBody()));
        Assertions.assertEquals(405, notAllowed.statusCode());
        Assertions.assertEquals("nosniff",
                headerOf(notAllowed, "x-content-type-options"), "a 405");
    }

    /**
     * Writes the file again, a second later than it was: a file system which keeps whole seconds would
     * otherwise report the same time for both versions, and the tag would say two different files are one.
     *
     * @param content to replace it with
     * @throws IOException if writing does
     */
    private void rewrite(final String content) throws IOException {
        final Path file = filesRoot.resolve("thing.txt");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder().uri(URI.create("http://" + HOST + ":" + server.port() + path));
    }

    private HttpResponse<byte[]> get(final String path,
                                     final String... headers) throws Exception {
        final HttpRequest.Builder request = request(path).GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return send(request);
    }

    private HttpResponse<byte[]> send(final HttpRequest.Builder request) throws Exception {
        return httpClient.send(request.build(), BodyHandlers.ofByteArray());
    }

    private static String etagOf(final HttpResponse<byte[]> response) {
        return headerOf(response, "ETag");
    }

    private static String headerOf(final HttpResponse<byte[]> response,
                                   final String name) {
        return response.headers().firstValue(name).orElse(null);
    }
}
