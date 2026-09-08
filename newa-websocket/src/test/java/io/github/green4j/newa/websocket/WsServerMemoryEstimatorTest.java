/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.server.NettyServer;
import io.github.green4j.newa.server.NettyServerBuilder;
import io.github.green4j.newa.server.ServerMemoryBudget;
import io.github.green4j.newa.server.ServerMemoryEstimate;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WsServerMemoryEstimatorTest {
    @Test
    void accountsForHandshakeAndEstablishedSessionPaths() {
        final ServerMemoryEstimate estimate = estimate(false);

        Assertions.assertEquals(177, estimate.heapBytesPerConnection());
        // two handshake bodies, not one: the exchange gate lets a connection hold the request behind the
        // one being answered - 2 x 100 outweighs the 180 an established session takes
        Assertions.assertEquals(211, estimate.directMemoryBytesPerConnection());
    }

    @Test
    void compressionAccountsForTheInflatedInboundAndEncodedOutboundBuffers() {
        final ServerMemoryEstimate estimate = estimate(true);

        // the same heap as without compression: the frame limit bounds the inflated frame as well, so what
        // reaches the application is the same size either way
        Assertions.assertEquals(177, estimate.heapBytesPerConnection());
        // direct memory is where compression shows: the arriving frame and the buffer it inflates into
        // coexist, and so do the outbound payload and its encoded copy
        Assertions.assertEquals(375, estimate.directMemoryBytesPerConnection());
    }

    @Test
    void arithmeticSaturatesInsteadOfWrapping() {
        final ServerMemoryEstimate estimate = WsServerMemoryEstimator.builder()
                .handshake(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
                .inboundFrame(Integer.MAX_VALUE)
                .outboundFrame(Integer.MAX_VALUE)
                .transport(Integer.MAX_VALUE, true)
                .additional(Long.MAX_VALUE, Long.MAX_VALUE)
                .estimate();

        Assertions.assertEquals(Long.MAX_VALUE, estimate.heapBytesPerConnection());
        Assertions.assertEquals(Long.MAX_VALUE, estimate.directMemoryBytesPerConnection());
    }

    @Test
    void aBudgetAndItsRequiredOutboundEstimateAreConfiguredTogether() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WsServer.of(new WsApiBuilder(1).build()).withMemoryBudget(
                        ServerMemoryBudget.builder().build(),
                        0
                )
        );
    }

    @Test
    void aCompressedBudgetIsBuiltOnTheFrameLimitTheInflatedFrameShares() throws Exception {
        // the frame limit is what the compression handler is given as its maximum decompression
        // allocation, so the estimate is built on a real number rather than netty's zero, which would have
        // meant an unbounded inflated frame
        final WsServer server = WsServer.of(new WsApiBuilder(1).build())
                .withCompression()
                .withMemoryBudget(ServerMemoryBudget.builder().build(), 1);

        try (NettyServer started = server.start(
                new NettyServerBuilder().host("127.0.0.1").port(0))) {
            final ServerMemoryEstimate estimate =
                    started.memoryRegistrationSnapshot().estimate();

            Assertions.assertEquals(
                    2L * WsServer.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH + 1,
                    estimate.heapBytesPerConnection()
            );
        }
    }

    private static ServerMemoryEstimate estimate(final boolean compression) {
        return WsServerMemoryEstimator.builder()
                .handshake(100, 10, 20)
                .inboundFrame(50)
                .outboundFrame(70)
                .transport(60, compression)
                .additional(7, 11)
                .estimate();
    }
}
