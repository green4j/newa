/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.rest;

/**
 * Renders what an error is answered with. The single funnel: everything which ends as an error response goes
 * through here, whether it was the routing which refused the request ({@link RestApiHandler}), the file
 * server ({@link io.github.green4j.newa.rest.files.FileServerHandler}), a handler which threw, or a handler
 * which called {@link RestHandle.Result#error}. Nothing else writes an error body.
 * <p>
 * One method, because the set of errors is open: a user's own exception extending {@link HttpException}
 * arrives here as it was thrown, with its own status, and typed overloads could never name it. So a whole
 * set of error pages is one lambda:
 * <pre>
 * RestServer.of(api).withErrorHandler(error -&gt; errorPages[error.status().code()]);
 * </pre>
 * The status of the response is {@link HttpException#status()} and is not this handler's to choose; what is
 * returned is only the body and the headers describing it.
 * <p>
 * This renders, and only renders. Reporting a failure to a log or a metric is
 * {@link HttpObserver#onResponseFailed}, which fires for the same errors and is given the original cause.
 * <p>
 * Called on the channel's event loop, so it must not block. Throwing from here fails the channel: the
 * response cannot be written, so the connection is closed and the cause goes to the
 * {@link io.github.green4j.newa.lang.ChannelErrorHandler}.
 */
@FunctionalInterface
public interface HttpErrorHandler {

    /**
     * @param error to answer; a failure which was not an {@link HttpException} arrives wrapped in an
     *              {@link InternalServerErrorException}, whose details must not be rendered
     * @return the body of the response, its content type and encoding
     */
    FullHttpResponseContent handle(HttpException error);

}
