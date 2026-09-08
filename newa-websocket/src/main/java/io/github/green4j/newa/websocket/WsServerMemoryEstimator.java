/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.server.ServerMemoryEstimate;

/**
 * Admission accounting for a WebSocket connection. The handshake path charges what the exchange gate lets a
 * connection hold before its session begins. The established-session path includes inbound frame
 * decoding, a coexisting heap-backed outbound payload, the configured outbound backlog and the frame which
 * crosses its high watermark. Text decoding is charged conservatively at two heap bytes per inbound byte,
 * including for a binary-only application. Compression charges the inbound frame twice, since the arriving
 * frame and the buffer it inflates into coexist, and includes the outbound source with a conservative upper
 * bound for its encoded buffer. A message of several frames is charged as one: nothing here collects them.
 */
final class WsServerMemoryEstimator {
    /**
     * A connection may own two requests at once: the one being answered, and one the codec had already
     * decoded from the same network read, which the exchange gate holds until that answer is written. The
     * handshake is one of them - it is an HTTP request like any other until it is answered.
     */
    private static final int REQUESTS_PER_CONNECTION = 2;

    private int maxContentLength;
    private int maxInitialLineLength;
    private int maxHeaderSize;
    private int maxFramePayloadLength;
    private int maxOutboundFramePayloadLength;
    private int writeBufferWaterMarkHigh;
    private boolean compression;
    private long additionalHeap;
    private long additionalDirectMemory;

    static WsServerMemoryEstimator builder() {
        return new WsServerMemoryEstimator();
    }

    WsServerMemoryEstimator handshake(final int contentLength,
                                      final int initialLineLength,
                                      final int headerSize) {
        maxContentLength = contentLength;
        maxInitialLineLength = initialLineLength;
        maxHeaderSize = headerSize;
        return this;
    }

    WsServerMemoryEstimator inboundFrame(final int framePayloadLength) {
        maxFramePayloadLength = framePayloadLength;
        return this;
    }

    WsServerMemoryEstimator outboundFrame(final int outboundFramePayloadLength) {
        maxOutboundFramePayloadLength = outboundFramePayloadLength;
        return this;
    }

    WsServerMemoryEstimator transport(final int writeWaterMarkHigh,
                                      final boolean compress) {
        writeBufferWaterMarkHigh = writeWaterMarkHigh;
        compression = compress;
        return this;
    }

    WsServerMemoryEstimator additional(final long heap,
                                       final long directMemory) {
        additionalHeap = heap;
        additionalDirectMemory = directMemory;
        return this;
    }

    ServerMemoryEstimate estimate() {
        final long handshakeHeap = multiply(
                add(maxInitialLineLength, maxHeaderSize),
                REQUESTS_PER_CONNECTION
        );
        final long frameHeap = multiply(maxFramePayloadLength, 2);
        final long establishedHeap = add(frameHeap, maxOutboundFramePayloadLength);
        final long heap = add(Math.max(handshakeHeap, establishedHeap), additionalHeap);

        final long inboundDirect = compression
                ? multiply(maxFramePayloadLength, 2)
                : maxFramePayloadLength;
        final long outboundDirect = compression
                ? add(
                        maxOutboundFramePayloadLength,
                        compressedUpperBound(maxOutboundFramePayloadLength)
                )
                : maxOutboundFramePayloadLength;
        final long establishedDirect = add(
                add(inboundDirect, writeBufferWaterMarkHigh),
                outboundDirect
        );
        final long direct = add(
                Math.max(
                        multiply(maxContentLength, REQUESTS_PER_CONNECTION),
                        establishedDirect
                ),
                additionalDirectMemory
        );
        return ServerMemoryEstimate.of(heap, direct);
    }

    private static long compressedUpperBound(final long sourceBytes) {
        long bound = sourceBytes;
        bound = add(bound, sourceBytes >>> 12);
        bound = add(bound, sourceBytes >>> 14);
        bound = add(bound, sourceBytes >>> 25);
        return add(bound, 64);
    }

    private static long multiply(final long value,
                                 final int multiplier) {
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    private static long add(final long left,
                            final long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
