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

package io.github.green4j.newa.rest;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A response nobody pulls: one which has nothing to give until something happens - a clock, a queue, a feed.
 * {@link ChunkedInput#readChunk} answers null, which suspends the transfer instead of ending it, and a later
 * flush resumes it. Between the two the connection costs no thread, which is the whole reason to serve a
 * response this way.
 * <p>
 * Nothing here is timed: the tick is delivered by hand, so what is pinned is the mechanism rather than a
 * scheduler. {@code ScheduledChunkedRestServer} in {@code newa-example} is the same mechanism with the
 * channel's own scheduler behind it.
 */
class SuspendingChunkedResponseTest {
    private static final AsciiString TEXT_PLAIN = AsciiString.cached("text/plain");

    /** Gives out whatever has been offered to it, and nothing at all in between. */
    private static final class SuspendingBody extends PushedResponseBody {
        private final List<String> pending = new ArrayList<>();

        private boolean closed;

        private void offer(final String line) {
            pending.add(line);
        }

        @Override
        protected ByteBuf next(final ByteBufAllocator allocator) {
            if (pending.isEmpty()) {
                return null; // suspends: null with no end in sight is "ask again when told to"
            }
            final byte[] bytes = pending.remove(0).getBytes(StandardCharsets.US_ASCII);
            return allocator.buffer(bytes.length).writeBytes(bytes);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final SuspendingBody body = new SuspendingBody();

    private final EmbeddedChannel channel = new EmbeddedChannel(
            new RestApiHandler(
                    buildTestApi(),
                    new JsonErrorHandler(),
                    (ch, cause) -> { }
            )
    );

    private RestApi buildTestApi() {
        final RestApiBuilder builder = new RestApiBuilder(
                "suspending-test",
                "suspending chunked tests",
                1,
                "test-build"
        );
        builder.get("/stream", (context, result) -> result.ok(TEXT_PLAIN, body));
        return builder.build();
    }

    @AfterEach
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    /**
     * @return everything written out since the last call: the head as its own entry, each chunk as its text
     */
    private List<String> drain() {
        final List<String> written = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof LastHttpContent) {
                written.add("<end>");
            } else if (outbound instanceof HttpContent) {
                written.add(((HttpContent) outbound).content().toString(StandardCharsets.US_ASCII));
            } else if (outbound instanceof HttpResponse) {
                final HttpResponse head = (HttpResponse) outbound;
                written.add("<head " + head.status().code() + " "
                        + head.headers().get(HttpHeaderNames.TRANSFER_ENCODING) + ">");
            }
            ReferenceCountUtil.release(outbound);
        }
        return written;
    }

    private void request() {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/v1/stream"
        ));
    }

    @Test
    public void testTheHeadGoesOutBeforeThereIsAnythingToSay() {
        request();

        Assertions.assertEquals(
                List.of("<head 200 " + HttpHeaderValues.CHUNKED + ">"),
                drain(),
                "the response has started even though the source has produced nothing");
    }

    @Test
    public void testWhatIsOfferedGoesOutOnTheNextFlush() {
        request();
        drain();

        body.offer("tick-1");
        channel.flush();

        Assertions.assertEquals(List.of("tick-1"), drain());
    }

    @Test
    public void testNothingGoesOutWhileThereIsNothingToSay() {
        request();
        drain();

        channel.flush();
        channel.flush();

        Assertions.assertEquals(List.of(), drain(),
                "a source with nothing to give must not end the response by saying so");
    }

    @Test
    public void testTheResponseGoesOnForAsLongAsTheChannelDoes() {
        request();

        for (int i = 0; i < 5; i++) {
            body.offer("tick-" + i);
            channel.flush();
        }

        final List<String> written = drain();
        Assertions.assertEquals(
                List.of("<head 200 chunked>", "tick-0", "tick-1", "tick-2", "tick-3", "tick-4"),
                written);
        Assertions.assertFalse(written.contains("<end>"),
                "nothing ended it, so it must not have ended");
    }

    @Test
    public void testTheSourceIsClosedWhenThePeerGoesAway() {
        request();
        drain();

        Assertions.assertFalse(body.closed, "the response is still running");

        channel.close();

        Assertions.assertTrue(body.closed,
                "whatever the source holds - a scheduled tick, a subscription - is released here or never");
    }
}
