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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A server under test, in a JVM of its own.
 * <p>
 * Sharing a JVM with the load client would make the measurement meaningless - the client's allocation and
 * its JIT profile would be the server's too - so each server is forked, given the workers the core split
 * leaves it, and killed when its runs are over. It is forked afresh for every client count, so no run
 * inherits another's warmed-up state.
 */
public final class ServerProcess implements AutoCloseable {
    private static final String LOCALHOST = "127.0.0.1";

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(90);
    private static final long READY_POLL_MILLIS = 100;
    private static final long STOP_TIMEOUT_SECONDS = 10;

    private final Process process;
    private final int port;

    private ServerProcess(final Process process,
                          final int port) {
        this.process = process;
        this.port = port;
    }

    /**
     * Forks a server and waits for it to answer.
     *
     * @param mainClass to run, the {@code *ServerMain} of the scenario being measured
     * @param server  name of the server to run, as that main class understands it
     * @param port    to listen on
     * @param workers threads the server is given. A server which does not take the setting ignores it
     * @param heap    to fix both {@code -Xms} and {@code -Xmx} at
     * @param options further {@code newa.perf.*} settings this scenario needs on the server side, as
     *                {@code name=value}. A scenario whose server generates the load - the WebSocket one
     *                publishes at a rate into a number of channels - is shaped by settings the REST one
     *                only ever gives its client, and a fork not told them would run at its own defaults
     * @return the running server
     * @throws IOException if the process could not be started
     * @throws InterruptedException if the calling thread is interrupted while waiting for it
     */
    public static ServerProcess start(final String mainClass,
                                      final String server,
                                      final int port,
                                      final int workers,
                                      final String heap,
                                      final String... options) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("-Xms" + heap);
        command.add("-Xmx" + heap);
        // the heap is committed before the first request rather than during the run
        command.add("-XX:+AlwaysPreTouch");
        command.add("-D" + BenchmarkOptions.PREFIX + "server=" + server);
        command.add("-D" + BenchmarkOptions.PREFIX + "port=" + port);
        command.add("-D" + BenchmarkOptions.PREFIX + "workers=" + workers);
        // the forked server must make the same choice this process did, not its own
        command.add("-D" + BenchmarkOptions.PREFIX + "transport="
                + BenchmarkOptions.property("transport", "auto"));
        for (int i = 0; i < options.length; i++) {
            command.add("-D" + BenchmarkOptions.PREFIX + options[i]);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);

        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start();

        final ServerProcess started = new ServerProcess(process, port);
        started.awaitReady(server);
        return started;
    }

    private void awaitReady(final String server) throws InterruptedException {
        final long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("The " + server + " server died before it was ready");
            }
            try {
                stats();
                return;
            } catch (final RuntimeException ignored) {
                TimeUnit.MILLISECONDS.sleep(READY_POLL_MILLIS);
            }
        }
        close();
        throw new IllegalStateException("The " + server + " server did not become ready in "
                + READY_TIMEOUT.toSeconds() + "s");
    }

    /**
     * @return what the server has collected and allocated so far
     */
    public JvmStats stats() {
        return JvmStats.fetch(LOCALHOST, port);
    }

    /**
     * The same, taken around a run rather than to find out whether the server is up. A sweep reaches rates at
     * which a server stops answering this endpoint at all, and letting that throw would end the sweep on its
     * most interesting row and lose every row before it. The row keeps what the client counted and says
     * {@code n/a} for the server's own cost.
     *
     * @return what the server has done so far, or {@link JvmStats#UNAVAILABLE} if it did not answer
     */
    public JvmStats statsOrUnavailable() {
        try {
            return stats();
        } catch (final RuntimeException e) {
            System.out.println("The server did not answer " + JvmStats.PATH
                    + ", so this row reports what arrived and nothing about what it cost: " + e.getCause());
            return JvmStats.UNAVAILABLE;
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
