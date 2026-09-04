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

package io.github.green4j.newa.websocket;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What one session reports to its observer, driven through an EmbeddedChannel so that nothing
 * depends on a network.
 */
class ObserverLifecycleTest {
    private static final class Observed implements WsApiObserver {
        private final List<String> stages = Collections.synchronizedList(new ArrayList<>());

        private long durationNanos = -1;

        @Override
        public void onSessionOpened(final ClientSession session) {
            stages.add("opened:" + (session != null));
        }

        @Override
        public void onFrameReceived(final int bytes) {
            stages.add("received:" + bytes);
        }

        @Override
        public void onFrameSent(final int bytes) {
            stages.add("sent:" + bytes);
        }

        @Override
        public void onWriteBackPressure(final int bytes) {
            stages.add("backPressure:" + bytes);
        }

        @Override
        public void onWriteFailed(final Throwable error) {
            stages.add("failed");
        }

        @Override
        public void onSessionClosed(final long durationNanos) {
            this.durationNanos = durationNanos;
            stages.add("closed");
        }
    }

    private static final class NoWritingResult implements WritingResult {
        @Override
        public void onWriteSuccess(final ClientSession session) {
        }

        @Override
        public void onWriteBackPressure(final ClientSession session) {
        }

        @Override
        public void onWriteError(final ClientSession session,
                                 final Throwable error) {
        }
    }

    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final WritingResult writingResult = new NoWritingResult();

    private ClientSession newSession(final ClientSessions sessions) {
        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);

        return sessions.newSession(
                new ClientSessionContext(
                        writingResult,
                        null,
                        channel,
                        0 // no pinger
                )
        );
    }

    @AfterEach
    void tearDown() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        channels.clear();
    }

    @Test
    void shouldAskTheFactoryOncePerSession() {
        final AtomicInteger asked = new AtomicInteger();
        final List<Observed> observers = new ArrayList<>();

        final ClientSessions sessions = new ClientSessions(null, () -> {
            asked.incrementAndGet();
            final Observed observer = new Observed();
            observers.add(observer);
            return observer;
        });

        final ClientSession first = newSession(sessions);
        final ClientSession second = newSession(sessions);

        Assertions.assertEquals(2, asked.get());
        Assertions.assertNotSame(observers.get(0), observers.get(1));

        first.send("a");

        Assertions.assertEquals(List.of("opened:true", "sent:1"), observers.get(0).stages);
        Assertions.assertEquals(List.of("opened:true"), observers.get(1).stages,
                "a session must report nothing another one did");

        second.close();
        first.close();
    }

    @Test
    void shouldReportTheBytesOfEveryFrameSent() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(null, () -> observer);

        final ClientSession session = newSession(sessions);

        final String text = "h\u00e9llo"; // six bytes of UTF-8, five characters
        Assertions.assertEquals(6, text.getBytes(StandardCharsets.UTF_8).length);

        session.send(text);
        session.send(text);
        session.close();

        Assertions.assertEquals(
                List.of("opened:true", "sent:6", "sent:6", "closed"),
                observer.stages
        );
    }

    @Test
    void shouldReportTheSessionClosedOnceWhateverTheNumberOfCloses() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(null, () -> observer);

        final ClientSession session = newSession(sessions);

        session.close();
        session.close();
        session.close();

        Assertions.assertEquals(List.of("opened:true", "closed"), observer.stages);
        Assertions.assertTrue(observer.durationNanos >= 0,
                "the session was open for a non negative time");
    }

    @Test
    void shouldReportTheSessionClosedEvenWhenTheApiTeardownThrows() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(
                new ClientSessionsListener() {
                    @Override
                    public void onSessionOpened(final ClientSession session) {
                    }

                    @Override
                    public void onSessionClosed(final ClientSession session) {
                        throw new IllegalStateException("the teardown of the api went wrong");
                    }
                },
                () -> observer
        );

        final ClientSession session = newSession(sessions);

        // close() is nearly always reached through CloseHelper.closeQuiet, which would swallow this and
        // leave the terminal event of the observer unsent - the one thing it is promised
        Assertions.assertThrows(IllegalStateException.class, session::close);

        Assertions.assertEquals(List.of("opened:true", "closed"), observer.stages);
    }

    @Test
    void shouldNotBroadcastToASessionTheApiFailedToOpen() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(
                new ClientSessionsListener() {
                    @Override
                    public void onSessionOpened(final ClientSession session) {
                        throw new IllegalStateException("whatever the api keeps per session is missing");
                    }

                    @Override
                    public void onSessionClosed(final ClientSession session) {
                    }
                },
                () -> observer
        );

        Assertions.assertThrows(IllegalStateException.class, () -> newSession(sessions));

        sessions.broadcastText("hello"); // a session which was never assembled is not in the fan-out

        final Object farewell = channels.get(0).readOutbound();
        Assertions.assertInstanceOf(CloseWebSocketFrame.class, farewell,
                "the only thing written is the status the session was closed with");
        Assertions.assertEquals(WebSocketCloseStatus.INTERNAL_SERVER_ERROR.code(),
                ((CloseWebSocketFrame) farewell).statusCode(),
                "an api which threw on the way in is the server breaking, not the peer misbehaving");
        ReferenceCountUtil.release(farewell);

        Assertions.assertNull(channels.get(0).readOutbound(), "and the broadcast did not reach it");
        Assertions.assertEquals(List.of("closed"), observer.stages);
    }

    @Test
    void shouldObserveNothingWithoutAnObserver() {
        final ClientSessions withoutFactory = new ClientSessions(null);
        final ClientSession first = newSession(withoutFactory);
        first.send("a");
        first.close();

        // a factory is free to refuse to observe a session, and nothing may notice
        final ClientSessions refusingFactory = new ClientSessions(null, () -> null);
        final ClientSession second = newSession(refusingFactory);
        second.send("a");
        second.close();

        Assertions.assertTrue(first.isClosed());
        Assertions.assertTrue(second.isClosed());
    }

    @Test
    void shouldReportAWriteWhichFailedAndTheSessionItEnded() {
        final Observed observer = new Observed();
        final WsApi api = new WsApiBuilder(1)
                .withObservers(() -> observer)
                .build();

        final EmbeddedChannel channel = new EmbeddedChannel();
        channels.add(channel);

        final ClientSession session = api.newSession(
                new ClientSessionContext(
                        api,
                        null,
                        channel,
                        0 // no pinger
                )
        );

        channel.close(); // the channel is gone from under the session
        session.send("abc");

        Assertions.assertEquals(List.of("opened:true", "failed", "closed"), observer.stages);
        Assertions.assertTrue(session.isClosed());
    }

    @Test
    void shouldReportTheFrameWhichDidNotGoOut() {
        final Observed observer = new Observed();
        final ClientSessions sessions = new ClientSessions(null, () -> observer);

        final ClientSession session = newSession(sessions);

        final EmbeddedChannel channel = channels.get(channels.size() - 1);
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        Assertions.assertFalse(channel.isWritable());

        session.send("abc");

        Assertions.assertEquals(List.of("opened:true", "backPressure:3"), observer.stages);

        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
        session.close();
    }
}
