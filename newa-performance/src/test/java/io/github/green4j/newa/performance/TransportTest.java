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
