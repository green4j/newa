/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.example.rest;

import io.github.green4j.newa.rest.HttpException;
import io.github.green4j.newa.rest.RestApiObserver;
import io.github.green4j.newa.rest.RestApiObserverFactory;
import io.github.green4j.newa.rest.RestContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * One of these per request, which is what lets a stage that runs a minute later still know what it is talking
 * about: everything it prints was copied while it was there to copy.
 */
public class StdOutRestApiObserver implements RestApiObserver {
    /**
     * @return a factory making one of these per request
     */
    public static RestApiObserverFactory factory() {
        return StdOutRestApiObserver::new;
    }

    private HttpMethod method;
    private String uri;

    /**
     * The endpoint expression, once routing has found it - that is what a metric is labelled by, being one of
     * a fixed set where the URI is one of unboundedly many. Null for a request which matched nothing.
     */
    private String pathExpression;

    private String name() {
        return pathExpression != null ? pathExpression : uri;
    }

    @Override
    public void onRequestReceived(final ChannelHandlerContext ctx,
                                  final HttpRequest request) {
        // the request does not outlive its handler, these do
        method = request.method();
        uri = request.uri();
        System.out.printf("-> %s %s%n", method, uri);
    }

    @Override
    public void onHandlingStarted(final RestContext context) {
        pathExpression = context.pathExpression();
        System.out.printf("   handling %s%n", pathExpression);
    }

    @Override
    public void onHandlingFinished(final HttpResponseStatus status,
                                   final long bytes,
                                   final long durationNanos) {
        System.out.printf("   handled %s: %s, %d bytes in %d us%n",
                pathExpression, status, bytes, durationNanos / 1000);
    }

    @Override
    public void onRequestNotRouted(final HttpException cause) {
        System.out.printf("   not routed: %s %s%n", method, uri);
    }

    @Override
    public void onResponseFailed(final HttpResponseStatus status,
                                 final Throwable error) {
        System.out.printf("   failed %s: %s %s%n", name(), status, error);
    }

    @Override
    public void onCursorOpened(final int openCursors) {
        System.out.printf("   cursor opened for %s, %d open%n", name(), openCursors);
    }

    @Override
    public void onCursorRefused(final int openCursors) {
        System.out.printf("   cursor refused for %s, %d already open%n", name(), openCursors);
    }

    @Override
    public void onCursorClosed(final int openCursors,
                               final long bytes,
                               final long durationNanos,
                               final Outcome outcome) {
        System.out.printf("   cursor %s for %s: %d bytes in %.1f ms, %d left%n",
                outcome, name(), bytes, durationNanos / 1e6, openCursors);
    }

    @Override
    public void onRequestCompleted(final HttpResponseStatus status,
                                   final long bytes,
                                   final long durationNanos) {
        System.out.printf("<- %s %s %s, %d bytes in %.1f ms%n",
                method, name(), status, bytes, durationNanos / 1e6);
    }
}
