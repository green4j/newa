/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * A request answered by a limit at the front of the pipeline and passed on to nothing - {@code 413},
 * {@code 414}, {@code 431}, {@code 400}. Nothing further in learns that it happened, so it is reported here.
 * <p>
 * A seam rather than an interface to implement directly: each server plugs in the reporting it already has.
 */
@FunctionalInterface
public interface RefusedRequestObserver {
    /**
     * Called on the event loop, after the answer has been written.
     *
     * @param ctx of the channel it arrived on
     * @param request refused. What a decoder refused is the substitute it built - {@code GET /bad-request} -
     *                rather than what the peer sent; {@code request.decoderResult().isFailure()} tells them
     *                apart
     * @param status it is answered with
     * @param cause as the decoder or the aggregator recorded it, never null
     */
    void onRequestRefused(ChannelHandlerContext ctx,
                          HttpRequest request,
                          HttpResponseStatus status,
                          Throwable cause);
}
