/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.LoadResult;
import io.github.green4j.newa.performance.ws.spring.SpringWsApplication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the whole loop works at a rate nothing can be short of: every server delivers everything it
 * publishes, to every subscriber, on every channel, in order.
 * <p>
 * These are not measurements - a second is far too short and the client shares the machine with the server -
 * they are the check that the counts the measurements are read off actually count.
 */
public class WsLoadClientSmokeTest {
    private static final int CLIENTS = 4;
    private static final int CHANNELS = 2;
    private static final long RATE = 100;
    private static final int DURATION_SECONDS = 1;

    @Test
    public void newaDeliversEveryChannelToEverySubscriber() throws Exception {
        assertDeliveredEverything(WsServerMain.NEWA);
    }

    @Test
    public void theSpringHandlerDeliversEveryChannelToEverySubscriber() throws Exception {
        assertDeliveredEverything(SpringWsApplication.RAW);
    }

    @Test
    public void theStompBrokerDeliversEveryChannelToEverySubscriber() throws Exception {
        assertDeliveredEverything(SpringWsApplication.STOMP);
    }

    private static void assertDeliveredEverything(final String server) throws Exception {
        final LoadResult result = run(server);

        assertEquals(0, result.gaps(), server + " skipped, which the no-skip mode forbids");
        assertEquals(0, result.ioErrors(), server + " disconnected a subscriber");
        assertTrue(result.requests() > 0, server + " delivered nothing");
        assertEquals(result.published(), result.requests(),
                server + " delivered less than it published");
        assertEquals(result.requests(), result.latencies().getTotalCount(),
                "Every delivered message has to be timed");

        // every subscriber takes every channel on its one connection, so a run which lost a channel
        // outright would still look busy without this
        final long expected = (long) CLIENTS * CHANNELS * RATE * DURATION_SECONDS;
        assertTrue(result.requests() > expected / 2,
                server + " delivered " + result.requests() + " of about " + expected);
    }

    private static LoadResult run(final String server) throws Exception {
        try (WsServer running = WsServerMain.start(server, 0, 2, CHANNELS, WsPayload.DEFAULT_SIZE, RATE)) {
            final Publisher[] publishers = WsServerMain.publish(running, CHANNELS, RATE);
            try (WsLoadClient client = new WsLoadClient(
                    "127.0.0.1", running.port(), CLIENTS, CHANNELS, WsClientMain.isStomp(server))) {
                return client.run(1, DURATION_SECONDS);
            } finally {
                WsServerMain.stop(publishers);
            }
        }
    }
}
