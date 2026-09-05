/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.ws;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.Cores;
import io.github.green4j.newa.performance.ws.newa.NewaWsServer;
import io.github.green4j.newa.performance.ws.spring.SpringWsApplication;
import io.github.green4j.newa.performance.ws.spring.SpringWsServer;

import java.util.concurrent.CountDownLatch;

/**
 * Starts one of the servers under test, with its publishers, and leaves it running.
 * <p>
 * This is what {@code ServerProcess} forks and what the {@code wsServer} Gradle task runs, so a server
 * profiled by hand is the same process the benchmark measures.
 * <p>
 * The publishers live here rather than inside the servers on purpose: the generation of the messages is
 * meant to be identical on all three, one thread per channel keeping the same open loop schedule, so that
 * what a run compares is the delivery.
 */
public final class WsServerMain {
    /**
     * newa, with a non-skipping subscription channel per publisher.
     */
    public static final String NEWA = "newa";

    private WsServerMain() {
    }

    /**
     * @param server   {@link #NEWA}, {@link SpringWsApplication#RAW} or {@link SpringWsApplication#STOMP}
     * @param port     to listen on, or 0 for an ephemeral one
     * @param workers  delivery threads, where the server takes the setting
     * @param channels to publish into
     * @param messageSize bytes a published message is
     * @param rate     each channel will publish at, which is what the allowance a subscriber is given
     *                 follows from - see {@link WsServer#outboundBudgetBytes(int, long, int)}
     * @return the running server, not yet publishing
     * @throws InterruptedException if the calling thread is interrupted while binding
     */
    public static WsServer start(final String server,
                                 final int port,
                                 final int workers,
                                 final int channels,
                                 final int messageSize,
                                 final long rate) throws InterruptedException {
        if (NEWA.equalsIgnoreCase(server)) {
            return NewaWsServer.start(port, workers, channels, messageSize, rate);
        }
        if (SpringWsApplication.RAW.equalsIgnoreCase(server)
                || SpringWsApplication.STOMP.equalsIgnoreCase(server)) {
            return SpringWsServer.start(server, port, workers, channels, messageSize, rate);
        }
        throw new IllegalArgumentException("Unknown server: " + server + ". Expected '" + NEWA
                + "', '" + SpringWsApplication.RAW + "' or '" + SpringWsApplication.STOMP + "'");
    }

    /**
     * Gives a server one publisher thread per channel and sets them going.
     *
     * @param server   to publish into
     * @param channels it has
     * @param rate     messages per second each channel publishes at
     * @return the running publishers, to be stopped before the server is closed
     */
    public static Publisher[] publish(final WsServer server,
                                      final int channels,
                                      final long rate) {
        final Publisher[] publishers = new Publisher[channels];
        for (int i = 0; i < channels; i++) {
            final int channel = i;
            publishers[i] = new Publisher(channel, rate, () -> server.publish(channel));
            publishers[i].start();
        }
        return publishers;
    }

    /**
     * Stops publishers started by {@link #publish}.
     *
     * @param publishers to stop
     */
    public static void stop(final Publisher[] publishers) {
        for (int i = 0; i < publishers.length; i++) {
            publishers[i].stopAndJoin();
        }
    }

    public static void main(final String[] args) throws Exception {
        final String server = BenchmarkOptions.property("server", NEWA);
        final int port = Integer.parseInt(BenchmarkOptions.property("port", "9100"));
        final int workers = Integer.parseInt(
                BenchmarkOptions.property("workers", Integer.toString(Cores.serverThreads())));
        final int channels = Integer.parseInt(BenchmarkOptions.property("channels", "1"));
        final int messageSize = Integer.parseInt(
                BenchmarkOptions.property("message", Integer.toString(WsPayload.DEFAULT_SIZE)));
        final long rate = BenchmarkOptions.longs(BenchmarkOptions.property("rate", "1000"))[0];

        final WsServer running = start(server, port, workers, channels, messageSize, rate);
        final Publisher[] publishers = publish(running, channels, rate);

        System.out.printf("%s listening on ws://127.0.0.1:%d%s, workers=%d, channels=%d, "
                        + "rate=%d msg/s, message=%dB, lag=%dms (valve %dB)%n",
                server, running.port(), WsPayload.PATH, workers, channels, rate, messageSize,
                BenchmarkOptions.lagMillisProperty(), WsServer.outboundBudgetBytes(channels, rate, messageSize));

        final CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop(publishers);
            running.close();
            stopped.countDown();
        }));
        stopped.await();
    }
}
