package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.ws.spring.SpringWsApplication;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an out of order STOMP stream comes from, established rather than assumed.
 * <p>
 * The chain is one publisher thread, one destination, one subscriber, one connection, and TCP under all of
 * it - so the order ought to survive, and the first suspect for a stream which arrives shuffled is the
 * benchmark's own client rather than the framework. This holds the framework to it with a different client
 * altogether - the JDK's, whose frames are handed over one at a time by the JDK's own thread - and then
 * changes exactly one thing about the server.
 * <p>
 * {@code clientOutboundChannel} is an {@code ExecutorSubscribableChannel}: Boot gives it
 * {@code availableProcessors() * 2} threads, so two consecutive publications to one destination are two
 * tasks two threads may finish in either order. Pinning that pool to a single thread is the only difference
 * between the two runs here, and it is what decides the question.
 */
public class StompDeliveryOrderTest {
    private static final long RATE = 5000;
    private static final int MESSAGES = 4000;
    private static final int TIMEOUT_SECONDS = 60;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    public void oneThreadOnTheOutboundChannelDeliversInOrder() throws Exception {
        final int outOfOrder = outOfOrderWith("1");
        assertEquals(0, outOfOrder,
                "With a single outbound thread the broker has nothing to reorder with, so a shuffled "
                        + "stream here would be the client's doing and not the framework's");
    }

    @Test
    public void theDefaultPoolIsWhatShufflesTheStream() throws Exception {
        final int pinned = outOfOrderWith("1");
        final int byDefault = outOfOrderWith("0"); // 0 leaves Boot's own pool alone

        assertEquals(0, pinned);
        assertTrue(byDefault > 0,
                "Boot's outbound pool delivered " + MESSAGES + " publications of one destination to one "
                        + "subscriber in order, which would mean the shuffle seen in a benchmark run comes "
                        + "from somewhere else and this test no longer says where");
    }

    /**
     * @param outbound threads to pin the broker's outbound channel to, {@code "0"} to leave Boot's default
     * @return how many messages arrived after one which was published later
     */
    private int outOfOrderWith(final String outbound) throws Exception {
        final String previous = System.getProperty(BenchmarkOptions.PREFIX + "outbound");
        System.setProperty(BenchmarkOptions.PREFIX + "outbound", outbound);
        try (WsServer running = WsServerMain.start(SpringWsApplication.STOMP, 0, 2, 1,
                WsPayload.DEFAULT_SIZE, RATE)) {
            final Publisher[] publishers = WsServerMain.publish(running, 1, RATE);
            try {
                final int backwards = outOfOrder(collect(running.port()));
                System.out.printf("outbound=%s: %d of %d messages arrived after a later one%n",
                        outbound, backwards, MESSAGES);
                return backwards;
            } finally {
                WsServerMain.stop(publishers);
            }
        } finally {
            if (previous == null) {
                System.clearProperty(BenchmarkOptions.PREFIX + "outbound");
            } else {
                System.setProperty(BenchmarkOptions.PREFIX + "outbound", previous);
            }
        }
    }

    private static int outOfOrder(final long[] sequences) {
        int backwards = 0;
        for (int i = 1; i < sequences.length; i++) {
            if (sequences[i] <= sequences[i - 1]) {
                backwards++;
            }
        }
        return backwards;
    }

    /**
     * @param port to subscribe to
     * @return the sequence numbers in the order they came off the wire
     */
    private long[] collect(final int port) throws Exception {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final WebSocket socket = http.newWebSocketBuilder()
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + WsPayload.PATH), new Collector(received))
                .get(10, TimeUnit.SECONDS);
        try {
            socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nhost:127.0.0.1\n\n\0", true)
                    .get(5, TimeUnit.SECONDS);
            received.poll(10, TimeUnit.SECONDS); // CONNECTED
            socket.sendText("SUBSCRIBE\nid:0\ndestination:" + WsPayload.TOPIC
                    + WsPayload.channelId(0) + "\n\n\0", true).get(5, TimeUnit.SECONDS);

            final long[] sequences = new long[MESSAGES];
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            int count = 0;
            while (count < MESSAGES && System.nanoTime() < deadline) {
                final String frame = received.poll(1, TimeUnit.SECONDS);
                if (frame == null) {
                    continue;
                }
                final int start = frame.indexOf('{');
                if (start < 0) {
                    continue;
                }
                sequences[count++] = WsPayload.readSequence(
                        Unpooled.wrappedBuffer(frame.getBytes(StandardCharsets.US_ASCII)), start);
            }
            if (count < MESSAGES) {
                throw new IllegalStateException("Only " + count + " of " + MESSAGES + " arrived");
            }
            return sequences;
        } finally {
            socket.abort();
        }
    }

    /**
     * Collects whole messages, joining the fragments the JDK client may hand them over in. The JDK calls
     * this back from one thread at a time and in the order the frames arrived, so nothing here can be the
     * thing which shuffles a stream.
     */
    private static final class Collector implements WebSocket.Listener {
        private final BlockingQueue<String> received;
        private final StringBuilder pending = new StringBuilder();

        private Collector(final BlockingQueue<String> received) {
            this.received = received;
        }

        @Override
        public CompletionStage<?> onText(final WebSocket socket,
                                         final CharSequence data,
                                         final boolean last) {
            pending.append(data);
            if (last) {
                received.add(pending.toString());
                pending.setLength(0);
            }
            socket.request(1);
            return null;
        }
    }
}
