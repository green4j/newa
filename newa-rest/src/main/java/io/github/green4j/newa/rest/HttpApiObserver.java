package io.github.green4j.newa.rest;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * The whole life cycle of one request, whether it was routed to an endpoint or not. The library keeps no
 * metrics of its own: it reports, and what that turns into is yours.
 * <p>
 * One of these observes one request, from {@link #onRequestReceived} to {@link #onRequestCompleted}, and
 * {@link HttpApiObserverFactory} is what produces them. The channel and the request are handed over once, at
 * the start, and never repeated: an observer made per request has somewhere to keep what it needs, and a
 * shared one has taken on that problem knowingly.
 * <p>
 * {@link #onRequestCompleted} fires exactly once, whatever form the response took - written in one piece,
 * rendered as an error, or pulled chunk by chunk from a cursor. Counting requests never means adding two
 * different events up.
 * <p>
 * {@link RestApiObserver} extends this with the stages of a request which reached an endpoint.
 * <p>
 * Every method has a no-op default. Calls come from event loop threads, so an implementation must not block.
 */
public interface HttpApiObserver {
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
    default void onRequestNotRouted(RestException cause) {
    }

    /**
     * The response reached the channel in full. The last thing reported about this request.
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
