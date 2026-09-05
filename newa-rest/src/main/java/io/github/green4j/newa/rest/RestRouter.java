/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.FullHttpRequest;

/**
 * What turns a request into the handle which answers it. {@link RestApi} is the implementation; a
 * {@link RestApiHandler} takes this, so an api of your own can be routed by whatever rules it likes.
 */
public interface RestRouter {

    RestHandling resolve(FullHttpRequest request) throws
            MethodNotAllowedException,
            PathNotFoundException;
}
