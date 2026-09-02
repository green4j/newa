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
