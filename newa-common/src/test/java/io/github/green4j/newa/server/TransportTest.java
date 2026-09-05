/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TransportTest {

    @Test
    public void nioIsAlwaysNio() {
        final Transport nio = Transport.nio();

        Assertions.assertEquals("nio", nio.name());
        Assertions.assertEquals(NioServerSocketChannel.class, nio.serverSocketChannel());
        Assertions.assertNotNull(nio.ioHandlerFactory());
        Assertions.assertSame(Transport.nio(), nio);
    }

    @Test
    public void autoIsResolvedOnceAndUsable() {
        final Transport auto = Transport.auto();

        Assertions.assertSame(Transport.auto(), auto);
        Assertions.assertNotNull(auto.ioHandlerFactory());
        Assertions.assertNotNull(auto.serverSocketChannel());
        Assertions.assertTrue(
                "kqueue".equals(auto.name())
                        || "epoll".equals(auto.name())
                        || "nio".equals(auto.name()),
                "unexpected transport: " + auto.name()
        );
    }

    @Test
    public void autoFallsBackQuietlyWithoutANativeArtifact() {
        // this module is built against netty-transport alone, so neither native transport is on the test
        // classpath - which makes this the assertion that the lookup fails in silence rather than throwing
        Assertions.assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.netty.channel.epoll.Epoll")
        );
        Assertions.assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("io.netty.channel.kqueue.KQueue")
        );

        Assertions.assertEquals("nio", Transport.auto().name());
        Assertions.assertSame(Transport.nio(), Transport.auto());
    }
}
