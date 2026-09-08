/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.server.RefusedRequestObserver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Gives a request refused at the front of the pipeline the same bracket every other request gets. The limits
 * answer ahead of the handler which would otherwise ask for an observer, so this asks instead, and the whole
 * bracket goes in one call because the whole request is over in one.
 * <p>
 * {@link AbstractHttpServer#withObservers(HttpObserverFactory)} builds one and hands it to the two handlers
 * which refuse. A pipeline written out by hand gives one to
 * {@link io.github.green4j.newa.server.ObservedHttpObjectAggregator} and to
 * {@link io.github.green4j.newa.server.DecoderFailureHandler} itself.
 */
public final class RefusedRequestReporter implements RefusedRequestObserver {
    private final HttpObserverFactory observers;

    /**
     * @param observers asked for an observer per refused request - the same factory the api was given, or
     *                  one server is counted as two
     */
    public RefusedRequestReporter(final HttpObserverFactory observers) {
        this.observers = observers;
    }

    @Override
    public void onRequestRefused(final ChannelHandlerContext ctx,
                                 final HttpRequest request,
                                 final HttpResponseStatus status,
                                 final Throwable cause) {
        final HttpObserver observer = observers.newObserver();
        if (observer == null) {
            return;
        }

        observer.onRequestReceived(ctx, request);
        observer.onRequestRefused(status, cause);
        // zero rather than a clock started at the refusal: that would measure how long the answer took
        observer.onRequestCompleted(status, 0, 0);
    }
}
