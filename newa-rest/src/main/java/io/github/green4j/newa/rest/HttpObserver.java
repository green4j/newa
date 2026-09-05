/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * The whole life cycle of one HTTP request, whoever answered it - an endpoint, the file server, or nothing at
 * all. The library keeps no metrics of its own: it reports, and what that turns into is yours.
 * <p>
 * One of these observes one request, from {@link #onRequestReceived} to {@link #onRequestCompleted}, and
 * {@link HttpObserverFactory} is what produces them. The channel and the request are handed over once, at
 * the start, and never repeated: an observer made per request has somewhere to keep what it needs, and a
 * shared one has taken on that problem knowingly.
 * <p>
 * {@link #onRequestCompleted} fires exactly once, whatever form the response took - written in one piece,
 * rendered as an error, or pulled chunk by chunk from a cursor. Counting requests never means adding two
 * different events up.
 * <p>
 * Anything which ends as an error response is reported here exactly once, by the stage which knows most
 * about it: {@link #onRequestNotRouted} when nothing served the request, {@link #onResponseFailed} otherwise.
 * That is where the cause of a {@code 500} is to be found - the response itself says only the status,
 * whatever the {@link HttpErrorHandler} would have rendered. A failure of the channel rather than of a
 * request goes to the {@link io.github.green4j.newa.lang.ChannelErrorHandler} instead, and a body larger than the pipeline
 * accepts is answered {@code 413} by Netty's aggregator, ahead of any of this.
 * <p>
 * {@link RestApiObserver} extends this with the stages of a request which reached an endpoint. Those nest
 * inside these and never cross them: {@link RestApiObserver#onHandlingFinished} closes the handling bracket
 * immediately before {@link #onRequestCompleted} closes the request.
 * <p>
 * Every method has a no-op default. Calls come from event loop threads, so an implementation must not block.
 */
public interface HttpObserver {
    /**
     * Read off the channel, not yet routed. The first stage, and the only one given the channel and the
     * request - so whatever a later stage needs is copied here. The request\'s body is only readable until
     * the handler returns.
     *
     * @param ctx of the channel it came in on
     * @param request that arrived
     */
    default void onRequestReceived(ChannelHandlerContext ctx,
                                   HttpRequest request) {
    }

    /**
     * Matched no endpoint, so no handling ever began and nothing behind the API was touched. The error
     * response which follows is reported by {@link #onRequestCompleted} as usual.
     *
     * @param cause of it - {@link PathNotFoundException} or {@link MethodNotAllowedException}, carrying the
     *              status it will be answered with
     */
    default void onRequestNotRouted(HttpException cause) {
    }

    /**
     * The request was served by something - an endpoint, the file server - and that failed. Never fires for a
     * request which reached nothing: that one is {@link #onRequestNotRouted}.
     * <p>
     * A failure before anything was written ends in an error response, which {@link #onRequestCompleted}
     * then reports as usual, and {@code status} is the one it is answered with. A failure after the head was
     * written cannot: the status already promised stands, the peer is left with a body short of the
     * {@code Content-Length} it was given, and the connection is closed under it rather than left carrying a
     * response which will not end. Either way this is the only place the cause is told, and
     * {@code bytes} of {@link #onRequestCompleted} is what really reached the channel.
     *
     * @param status responded with
     * @param error that caused it, as it was thrown rather than as it was wrapped to be answered. The only
     *              place a failure is reported in full, so this is what a log wants
     */
    default void onResponseFailed(HttpResponseStatus status,
                                  Throwable error) {
    }

    /**
     * The response reached the channel in full. The last thing reported about this request, always - a
     * request which reached an endpoint has had {@link RestApiObserver#onHandlingFinished} just before it.
     *
     * @param status responded with
     * @param bytes of content - for a chunked response, the body as the cursor produced it, without the
     *              framing the transfer encoding adds
     * @param durationNanos from the request arriving to the last byte reaching the channel
     */
    default void onRequestCompleted(HttpResponseStatus status,
                                    long bytes,
                                    long durationNanos) {
    }
}
