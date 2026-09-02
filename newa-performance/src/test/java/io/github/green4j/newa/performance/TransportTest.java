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

package io.github.green4j.newa.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transport half chosen - a native handler with a NIO channel, say - fails at run time with an error which
 * says nothing about the cause, and only under load. This is cheaper.
 */
public class TransportTest {

    @Test
    public void everythingComesFromTheSameFamily() {
        final String name = Transport.name();
        final String socket = Transport.socketChannel().getSimpleName();
        final String server = Transport.serverSocketChannel().getSimpleName();

        assertNotNull(Transport.ioHandlerFactory());

        if (name.startsWith("kqueue")) {
            assertEquals("KQueueSocketChannel", socket);
            assertEquals("KQueueServerSocketChannel", server);
            return;
        }
        if (name.startsWith("epoll")) {
            assertEquals("EpollSocketChannel", socket);
            assertEquals("EpollServerSocketChannel", server);
            return;
        }
        assertTrue(name.startsWith(Transport.NIO), name);
        assertEquals("NioSocketChannel", socket);
        assertEquals("NioServerSocketChannel", server);
    }

    @Test
    public void aNativeTransportIsUsedWhereThereIsOne() {
        final boolean nativeAvailable = io.netty.channel.kqueue.KQueue.isAvailable()
                || io.netty.channel.epoll.Epoll.isAvailable();
        if (nativeAvailable) {
            assertTrue(!Transport.name().startsWith(Transport.NIO),
                    "A native transport is available but " + Transport.name() + " was chosen");
        }
    }
}
