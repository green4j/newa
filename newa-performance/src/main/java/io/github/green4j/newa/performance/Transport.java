package io.github.green4j.newa.performance;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Which Netty transport the benchmark runs on, chosen for the machine it is running on: kqueue on macOS,
 * epoll on Linux, and NIO wherever neither is available.
 * <p>
 * The choice is not cosmetic here. What limits this benchmark is the cost of a request rather than the size
 * of one, and most of that cost is the trip through the kernel, so the transport is one of the few things
 * which can move the ceiling at all. Both native artifacts are always on the classpath and both are safe to
 * load anywhere - {@code isAvailable()} is how each reports that it is not for this platform.
 * <p>
 * {@code -Ptransport=nio} forces the portable one, which is the way to measure what the native one is worth.
 * Note that only the newa server and the load client can take it: Tomcat has a transport of its own, so a
 * Spring run is unaffected either way.
 */
public final class Transport {
    /**
     * Value of {@code -Ptransport} which refuses the native transport even where there is one.
     */
    public static final String NIO = "nio";

    private static final boolean FORCED_NIO =
            NIO.equalsIgnoreCase(BenchmarkOptions.property("transport", "auto"));

    private Transport() {
    }

    /**
     * @return what to put in a report, so a number is never read without knowing what produced it
     */
    public static String name() {
        if (useKQueue()) {
            return "kqueue";
        }
        if (useEpoll()) {
            return "epoll";
        }
        if (FORCED_NIO) {
            return NIO;
        }
        return NIO + " (no native transport for this platform)";
    }

    /**
     * @return the handler factory an event loop group should be built with
     */
    public static IoHandlerFactory ioHandlerFactory() {
        if (useKQueue()) {
            return KQueueIoHandler.newFactory();
        }
        if (useEpoll()) {
            return EpollIoHandler.newFactory();
        }
        return NioIoHandler.newFactory();
    }

    /**
     * @return the channel a client connects with
     */
    public static Class<? extends SocketChannel> socketChannel() {
        if (useKQueue()) {
            return KQueueSocketChannel.class;
        }
        if (useEpoll()) {
            return EpollSocketChannel.class;
        }
        return NioSocketChannel.class;
    }

    /**
     * @return the channel a server listens on
     */
    public static Class<? extends ServerChannel> serverSocketChannel() {
        if (useKQueue()) {
            return KQueueServerSocketChannel.class;
        }
        if (useEpoll()) {
            return EpollServerSocketChannel.class;
        }
        return NioServerSocketChannel.class;
    }

    private static boolean useKQueue() {
        return !FORCED_NIO && KQueue.isAvailable();
    }

    private static boolean useEpoll() {
        return !FORCED_NIO && Epoll.isAvailable();
    }
}
