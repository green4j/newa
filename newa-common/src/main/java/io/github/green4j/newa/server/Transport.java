/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Which Netty transport a server runs on: kqueue on macOS, epoll on Linux, NIO wherever neither is
 * available.
 * <p>
 * The native ones are found by name rather than by a dependency - this module is built against
 * {@code netty-transport} alone, so no user of this library carries an epoll or kqueue jar it will never
 * bind a socket with. {@link #auto()} takes what happens to be on the classpath and falls back to NIO in
 * silence, which is also what it does when a native artifact is there but its classifier does not match the
 * machine.
 * <p>
 * The consequence worth knowing: nothing reports that you meant to run on epoll and did not. {@link #name()}
 * is what a server prints at startup, and {@link #nio()} asks for the portable one on purpose - in a GraalVM
 * native image, where a class found by name needs reachability metadata, that is the only one guaranteed to
 * resolve.
 */
public final class Transport {
    private static final String KQUEUE = "kqueue";
    private static final String EPOLL = "epoll";
    private static final String NIO = "nio";

    private static final String KQUEUE_PACKAGE = "io.netty.channel.kqueue.KQueue";
    private static final String EPOLL_PACKAGE = "io.netty.channel.epoll.Epoll";

    private static final Transport NIO_TRANSPORT = new Transport(
            NIO,
            NioIoHandler.newFactory(),
            NioServerSocketChannel.class
    );

    private static final Transport AUTO_TRANSPORT = resolve();

    /**
     * The best transport this machine and this classpath can give, resolved once, when this class is loaded.
     *
     * @return kqueue, epoll, or NIO where neither could be loaded.
     */
    public static Transport auto() {
        return AUTO_TRANSPORT;
    }

    /**
     * The portable transport, asked for on purpose: reproducible everywhere, and the only one which needs
     * nothing beyond {@code netty-transport}.
     *
     * @return the NIO transport.
     */
    public static Transport nio() {
        return NIO_TRANSPORT;
    }

    private static Transport resolve() {
        final Transport kqueue = nativeTransport(KQUEUE, KQUEUE_PACKAGE);
        if (kqueue != null) {
            return kqueue;
        }
        final Transport epoll = nativeTransport(EPOLL, EPOLL_PACKAGE);
        if (epoll != null) {
            return epoll;
        }
        return NIO_TRANSPORT;
    }

    /**
     * @param name of the transport, which is also the infix of every class it is made of.
     * @param availability the fully qualified name of the class whose static isAvailable() answers for it.
     * @return the transport, or null if any part of it could not be loaded, is not available on this
     *         machine, or refused to initialize.
     */
    private static Transport nativeTransport(final String name,
                                             final String availability) {
        try {
            final ClassLoader loader = Transport.class.getClassLoader();

            final Class<?> available = Class.forName(availability, true, loader);
            if (!Boolean.TRUE.equals(available.getMethod("isAvailable").invoke(null))) {
                return null;
            }

            final String prefix = availability.substring(0, availability.lastIndexOf('.') + 1)
                    + Character.toUpperCase(name.charAt(0)) + name.substring(1);

            // a cast to a non-generic interface and asSubclass rather than a cast to Class<? extends
            // ServerChannel>: both are checked, so this file needs no @SuppressWarnings to pass -Werror
            final IoHandlerFactory factory = (IoHandlerFactory) Class.forName(prefix + "IoHandler",
                            true, loader)
                    .getMethod("newFactory")
                    .invoke(null);

            final Class<? extends ServerChannel> channel = Class.forName(prefix + "ServerSocketChannel",
                            true, loader)
                    .asSubclass(ServerChannel.class);

            return new Transport(name, factory, channel);
        } catch (final Exception | LinkageError notThere) {
            // the artifact is absent, or its native library is not for this platform, or a security policy
            // refused the lookup - all of which mean the same thing here, and none of which is an error
            return null;
        }
    }

    private final String name;
    private final IoHandlerFactory ioHandlerFactory;
    private final Class<? extends ServerChannel> serverSocketChannel;

    private Transport(final String name,
                      final IoHandlerFactory ioHandlerFactory,
                      final Class<? extends ServerChannel> serverSocketChannel) {
        this.name = name;
        this.ioHandlerFactory = ioHandlerFactory;
        this.serverSocketChannel = serverSocketChannel;
    }

    /**
     * @return "kqueue", "epoll" or "nio" - what to print at startup, so a measurement is never read without
     *         knowing what produced it.
     */
    public String name() {
        return name;
    }

    /**
     * @return the handler factory an event loop group of this transport is built with.
     */
    public IoHandlerFactory ioHandlerFactory() {
        return ioHandlerFactory;
    }

    /**
     * @return the channel class a server of this transport listens on.
     */
    public Class<? extends ServerChannel> serverSocketChannel() {
        return serverSocketChannel;
    }

    @Override
    public String toString() {
        return name;
    }
}
