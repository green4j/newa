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

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The body which reads a file somewhere other than the event loop, driven by hand: the reads are run by the
 * test thread and the loop by {@link EmbeddedChannel#runPendingTasks()}, so what is being asked here is the
 * state machine and not a race.
 */
class ReadAheadFileTest {
    private static final int CHUNK = 1024;
    private static final int CHUNKS = 3;
    private static final int SIZE = CHUNK * CHUNKS;

    @TempDir
    private Path root;

    private final Deque<Runnable> reads = new ArrayDeque<>();

    private Recording allocator;
    private EmbeddedChannel channel;
    private ChunkedWriteHandler writer;
    private ChannelHandlerContext ctx;

    /**
     * Hands out buffers and remembers them, so that a test can say what became of one it never saw.
     */
    private static final class Recording extends AbstractByteBufAllocator {
        private final List<ByteBuf> given = new ArrayList<>();
        private boolean broken;

        @Override
        protected ByteBuf newHeapBuffer(final int initialCapacity,
                                        final int maxCapacity) {
            if (broken) {
                throw new IllegalStateException("No buffer for you");
            }
            return record(UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity));
        }

        @Override
        protected ByteBuf newDirectBuffer(final int initialCapacity,
                                          final int maxCapacity) {
            return record(UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity));
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        private ByteBuf record(final ByteBuf buffer) {
            given.add(buffer);
            return buffer;
        }
    }

    /**
     * Where the response would be written from, and the only thing this needs of the pipeline.
     */
    private static final class Tail extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(final ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }

    @BeforeEach
    public void setUp() {
        allocator = new Recording();
        writer = new ChunkedWriteHandler();
        final Tail tail = new Tail();
        channel = new EmbeddedChannel(writer, tail);
        channel.config().setAllocator(allocator);
        ctx = tail.ctx;
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private Path file(final int bytes) throws IOException {
        final byte[] content = new byte[bytes];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        final Path path = root.resolve("a.bin");
        Files.write(path, content);
        return path;
    }

    private static FileChannel open(final Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ);
    }

    private ReadAheadFile body(final FileChannel file,
                               final long length) throws IOException {
        return new ReadAheadFile(ctx, writer, reads::add, file, 0, length, CHUNK);
    }

    /**
     * Runs whatever reading is outstanding, and then whatever the loop was told about it.
     */
    private void drain() {
        while (!reads.isEmpty()) {
            reads.poll().run();
            channel.runPendingTasks();
        }
    }

    @Test
    public void testTheFirstChunkIsAskedForAndNotWaitedFor() throws Exception {
        final ReadAheadFile body = body(open(file(SIZE)), SIZE);

        Assertions.assertNull(body.readChunk(allocator), "the read has only just been asked for");
        Assertions.assertFalse(body.isEndOfInput(), "which is not the same as having nothing to send");
        Assertions.assertEquals(0, body.progress());
        Assertions.assertEquals(1, reads.size(), "one chunk is read ahead, and one only");

        drain();

        final ByteBuf chunk = body.readChunk(allocator);
        Assertions.assertNotNull(chunk);
        Assertions.assertEquals(CHUNK, chunk.readableBytes());
        Assertions.assertEquals(CHUNK, body.progress());
        chunk.release();

        body.close();
    }

    @Test
    public void testAChunkReadIsNotAChunkDelivered() throws Exception {
        final ReadAheadFile body = body(open(file(SIZE)), SIZE);

        Assertions.assertNull(body.readChunk(allocator));
        drain();
        final ByteBuf first = body.readChunk(allocator);
        first.release();

        drain(); // the second chunk is read while the first one is being written

        Assertions.assertEquals(CHUNK, body.progress(),
                "a chunk which is only read has reached nobody, and a response is judged by what reached the peer");
        Assertions.assertFalse(body.isEndOfInput());

        body.close();
    }

    @Test
    public void testTheWholeFileIsHandedOverInOrder() throws Exception {
        final Path path = file(SIZE);
        final ReadAheadFile body = body(open(path), SIZE);

        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        while (!body.isEndOfInput()) {
            final ByteBuf chunk = body.readChunk(allocator);
            if (chunk == null) {
                Assertions.assertTrue(reads.size() <= 1, "more than one read is in flight at a time");
                drain();
                continue;
            }
            final byte[] read = new byte[chunk.readableBytes()];
            chunk.getBytes(chunk.readerIndex(), read);
            sent.write(read, 0, read.length);
            chunk.release();
        }

        Assertions.assertArrayEquals(Files.readAllBytes(path), sent.toByteArray());
        Assertions.assertEquals(SIZE, body.progress());
        Assertions.assertEquals(SIZE, body.length());

        body.close();
    }

    @Test
    public void testTheFileIsGivenBackWhenTheTransferEnds() throws Exception {
        final FileChannel file = open(file(SIZE));
        final ReadAheadFile body = body(file, SIZE);

        Assertions.assertNull(body.readChunk(allocator));
        drain();

        body.close();

        Assertions.assertFalse(file.isOpen());
        assertNothingIsHeld();
    }

    @Test
    public void testAFileBeingReadIsGivenBackWhenTheReadComesBack() throws Exception {
        final FileChannel file = open(file(SIZE));
        final ReadAheadFile body = body(file, SIZE);

        Assertions.assertNull(body.readChunk(allocator)); // a read is in flight
        body.close();

        Assertions.assertTrue(file.isOpen(), "the read still running is reading from it");

        drain();

        Assertions.assertFalse(file.isOpen(), "and the read coming back is what gives it up");
        assertNothingIsHeld();
    }

    @Test
    public void testAReadWhichFailsIsReportedWhereTheChunkWouldHaveBeen() throws Exception {
        final FileChannel file = open(file(SIZE));
        final ReadAheadFile body = body(file, SIZE);

        allocator.broken = true; // a read which cannot even begin, and which has nowhere of its own to fail

        Assertions.assertNull(body.readChunk(allocator));
        drain();

        Assertions.assertThrows(IllegalStateException.class, () -> body.readChunk(allocator),
                "the pipeline asking for the chunk is the only place a read can be reported to");
        assertNothingIsHeld();

        body.close();
        Assertions.assertFalse(file.isOpen());
    }

    @Test
    public void testAFileWhichLostItsBytesEndsTheTransferShort() throws Exception {
        final Path path = file(SIZE);
        final ReadAheadFile body = body(open(path), SIZE);

        Files.write(path, new byte[CHUNK + 100]); // shorter than the length this response promised

        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        while (!body.isEndOfInput()) {
            final ByteBuf chunk = body.readChunk(allocator);
            if (chunk == null) {
                drain();
                continue;
            }
            sent.write(chunk.array(), chunk.arrayOffset() + chunk.readerIndex(), chunk.readableBytes());
            chunk.release();
        }

        Assertions.assertEquals(CHUNK + 100, sent.size());
        Assertions.assertEquals(CHUNK + 100, body.progress(),
                "which is short of the length promised, and that is what the response is closed on");
        Assertions.assertEquals(SIZE, body.length());

        body.close();
    }

    @Test
    public void testAFileWhichLostEverythingEndsAtOnce() throws Exception {
        final Path path = file(SIZE);
        final ReadAheadFile body = body(open(path), SIZE);

        Files.write(path, new byte[0]);

        Assertions.assertNull(body.readChunk(allocator));
        drain();

        Assertions.assertNull(body.readChunk(allocator), "there is nothing to send and nothing more to read");
        Assertions.assertTrue(body.isEndOfInput(), "so the response ends here rather than never");
        Assertions.assertEquals(0, body.progress());
        assertNothingIsHeld();

        body.close();
    }

    private void assertNothingIsHeld() {
        for (int i = 0; i < allocator.given.size(); i++) {
            Assertions.assertEquals(0, allocator.given.get(i).refCnt(),
                    "a buffer read into was never given back");
        }
    }
}
