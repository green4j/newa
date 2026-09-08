/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.budget;

import io.github.green4j.newa.budget.harness.BudgetServerHarness;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The worst case the per-connection estimate is written against: every connection announces a
 * maximum-sized body, sends all of it but the last byte, and reads nothing back.
 *
 * <p>Holding a byte back is the point. A body which arrives whole is answered and released, so a client
 * which completes its request holds nothing; one which stops just short leaves the aggregator sitting on
 * the whole of it, which is the memory the budget promised to have accounted for.
 */
final class SlowReaderFlood implements AutoCloseable {
    private static final int WRITE_CHUNK = 16 * 1024;

    private final List<Socket> sockets = new ArrayList<>();
    private final String host;
    private final int port;
    private final int bodyBytes;

    SlowReaderFlood(final String host,
                    final int port,
                    final int bodyBytes) {
        this.host = host;
        this.port = port;
        this.bodyBytes = bodyBytes;
    }

    /**
     * Opens connections until it has tried that many.
     *
     * @param connections to attempt
     * @return how many took the whole of their body, which is at most how many the server admitted: a
     *         refused connection is closed without a word and its write fails, sooner or later
     */
    int connect(final int connections) {
        int taken = 0;
        for (int i = 0; i < connections; i++) {
            if (send()) {
                taken++;
            }
        }
        return taken;
    }

    private boolean send() {
        final Socket socket = new Socket();
        try {
            socket.setSoTimeout(10_000);
            socket.connect(new InetSocketAddress(host, port), 10_000);
            sockets.add(socket);

            final OutputStream out = socket.getOutputStream();
            out.write(("POST " + BudgetServerHarness.SINK_PATH + " HTTP/1.1\r\n"
                    + "Host: " + host + ':' + port + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + bodyBytes + "\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));

            final byte[] chunk = new byte[WRITE_CHUNK];
            Arrays.fill(chunk, (byte) 'y');
            int written = 0;
            while (written < bodyBytes - 1) {
                final int length = Math.min(chunk.length, bodyBytes - 1 - written);
                out.write(chunk, 0, length);
                written += length;
            }
            out.flush();
            return true;
        } catch (final IOException refused) {
            // being refused is an outcome this asks for, not a failure: the server closes an inadmissible
            // connection without a word, and the write finds out whenever the reset arrives
            return false;
        }
    }

    int connections() {
        return sockets.size();
    }

    @Override
    public void close() {
        for (final Socket socket : sockets) {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // closing what is already gone
            }
        }
        sockets.clear();
    }
}
