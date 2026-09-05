/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * A {@link HttpObserverFactory} whose observers also see the stages after routing. Hand one of these to
 * {@link RestApiHandler} and every request is observed by a {@link RestApiObserver} rather than a plain
 * {@link HttpObserver}.
 */
@FunctionalInterface
public interface RestApiObserverFactory extends HttpObserverFactory {
    @Override
    RestApiObserver newObserver();
}
