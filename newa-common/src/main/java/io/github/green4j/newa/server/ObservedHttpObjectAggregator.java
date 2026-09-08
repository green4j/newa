/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.TooLongHttpContentException;

/**
 * Netty's {@link HttpObjectAggregator} with the one thing it does not do: saying that a body past
 * {@code maxContentLength} was refused. The {@code 413} is written by the aggregator itself, in front of
 * every handler behind it, so without this an oversized upload is a request the server never learns arrived.
 * <p>
 * Both routes to the limit - a declared {@code Content-Length} and a body which grows past it - come through
 * one override, and it delegates: what is answered stays Netty's decision.
 */
public class ObservedHttpObjectAggregator extends HttpObjectAggregator {
    private final RefusedRequestObserver observer;

    /**
     * @param maxContentLength the body of a request may be, above which it is answered {@code 413}.
     * @param closeOnExpectationFailed whether a {@code 100-continue} which is refused closes the connection.
     * @param observer told about each refused request, or null to say nothing.
     */
    public ObservedHttpObjectAggregator(final int maxContentLength,
                                        final boolean closeOnExpectationFailed,
                                        final RefusedRequestObserver observer) {
        super(maxContentLength, closeOnExpectationFailed);
        this.observer = observer;
    }

    @Override
    protected void handleOversizedMessage(final ChannelHandlerContext ctx,
                                          final HttpMessage oversized) throws Exception {
        super.handleOversizedMessage(ctx, oversized); // the answer first: an observer cannot delay a 413

        if (oversized instanceof HttpRequest) { // a response too large is not this server's to report
            final HttpRequest refused = (HttpRequest) oversized;
            final TooLongHttpContentException cause =
                    new TooLongHttpContentException("Request entity too large: " + refused.uri());
            Observed.by(observer, told -> told.onRequestRefused(
                    ctx,
                    refused,
                    HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    cause
            ));
        }
    }
}
