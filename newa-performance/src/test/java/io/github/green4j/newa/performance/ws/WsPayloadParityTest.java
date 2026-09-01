package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.ws.spring.SpringWsApplication;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test which keeps the comparison honest: whatever any of the three servers is changed to, all of
 * them have to publish the same bytes. A benchmark whose servers send different messages measures nothing.
 * <p>
 * They cannot simply be compared with each other: every field of a message is derived from its sequence
 * number, and by the time a subscriber arrives each server has published a different number of them. So each
 * is held to the same thing instead - the message {@link WsPayload} says that sequence should be - which is
 * a stricter test anyway, and the one which catches a server putting its own encoding around the payload.
 */
public class WsPayloadParityTest {
    private static final int CHANNELS = 2;
    private static final long RATE = 200;
    private static final int MESSAGE_TIMEOUT_SECONDS = 20;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    public void allThreeServersPublishTheSameMessage() throws Exception {
        for (final String server : new String[]{
                WsServerMain.NEWA, SpringWsApplication.RAW, SpringWsApplication.STOMP}) {
            final String published = firstMessageFrom(server);

            assertEquals(canonical(published).length(), published.length(),
                    server + " published " + published.length() + " bytes: " + published);
            assertTrue(published.startsWith("{\"type\":\"event\",\"seq\":"), server + ": " + published);
            assertEquals(canonical(published), published,
                    server + " published something other than the message its sequence calls for");
        }
    }

    /**
     * @param published a message as it came off the wire
     * @return what {@link WsPayload} says a message carrying that sequence and that instant has to be
     */
    private static String canonical(final String published) {
        final ByteBuf frame =
                Unpooled.wrappedBuffer(published.getBytes(StandardCharsets.US_ASCII));
        final long sequence = WsPayload.readSequence(frame, 0);
        final long publishedNanos = WsPayload.readPublishedNanos(frame, 0);

        return new String(
                WsPayload.render(0, sequence, publishedNanos, WsPayload.padding(WsPayload.DEFAULT_SIZE)),
                StandardCharsets.US_ASCII);
    }

    @Test
    public void everyServerReportsItsOwnStatistics() throws Exception {
        for (final String server : new String[]{
                WsServerMain.NEWA, SpringWsApplication.RAW, SpringWsApplication.STOMP}) {
            try (WsServer running = WsServerMain.start(server, 0, 1, 1, WsPayload.DEFAULT_SIZE, RATE)) {
                final HttpRequest request = HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + running.port() + JvmStats.PATH))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                final HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), server + " did not answer its statistics");
                assertTrue(response.body().contains("gcCount="), server + ": " + response.body());
            }
        }
    }

    /**
     * @param server to subscribe to
     * @return the first message it published
     */
    private String firstMessageFrom(final String server) throws Exception {
        try (WsServer running = WsServerMain.start(server, 0, 2, CHANNELS, WsPayload.DEFAULT_SIZE, RATE)) {
            final Publisher[] publishers = WsServerMain.publish(running, CHANNELS, RATE);
            try {
                return subscribeAndTakeOne(running.port(), WsClientMain.isStomp(server));
            } finally {
                WsServerMain.stop(publishers);
            }
        }
    }

    private String subscribeAndTakeOne(final int port,
                                       final boolean stomp) throws Exception {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final WebSocket.Builder builder = http.newWebSocketBuilder();
        if (stomp) {
            builder.subprotocols("v12.stomp");
        }

        final WebSocket socket = builder
                .buildAsync(URI.create("ws://127.0.0.1:" + port + WsPayload.PATH), new Collector(received))
                .get(10, TimeUnit.SECONDS);
        try {
            if (stomp) {
                socket.sendText("CONNECT\naccept-version:1.2\nheart-beat:0,0\nhost:127.0.0.1\n\n\0", true)
                        .get(5, TimeUnit.SECONDS);
                final String connected = received.poll(10, TimeUnit.SECONDS);
                assertNotNull(connected, "The broker did not answer CONNECT");
                assertTrue(connected.startsWith("CONNECTED"), connected);
                socket.sendText("SUBSCRIBE\nid:0\ndestination:" + WsPayload.TOPIC
                        + WsPayload.channelId(0) + "\n\n\0", true).get(5, TimeUnit.SECONDS);
            } else {
                socket.sendText(WsPayload.SUBSCRIBE + WsPayload.channelId(0), true)
                        .get(5, TimeUnit.SECONDS);
            }

            final long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(MESSAGE_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                final String frame = received.poll(1, TimeUnit.SECONDS);
                if (frame == null) {
                    continue;
                }
                final int start = frame.indexOf('{');
                final int end = frame.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    return frame.substring(start, end + 1);
                }
            }
            throw new IllegalStateException("Nothing was published within "
                    + MESSAGE_TIMEOUT_SECONDS + "s");
        } finally {
            socket.abort();
        }
    }

    /**
     * Collects whole messages, joining the fragments the JDK client may hand them over in.
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
