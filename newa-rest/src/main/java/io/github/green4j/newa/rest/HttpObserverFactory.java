/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * Produces the {@link HttpObserver} which observes one request. Asked once per request, before anything
 * else about it is known.
 * <p>
 * How much that costs is yours to decide. Return a new observer per request and every stage of that request
 * has somewhere private to keep what it copied; return one shared instance and nothing is allocated at all,
 * at the price of having to work out for yourself which request a call belongs to - across channels, across
 * threads, and across a chunked response which ends long after the requests that followed it. Return null and
 * the request is not observed at all: not even the clock is read for it.
 * <p>
 * Called from event loop threads, so it must not block, and must be safe to call from several at once.
 */
@FunctionalInterface
public interface HttpObserverFactory {
    /**
     * @return an observer for the request about to be read, or null to observe it not at all
     */
    HttpObserver newObserver();
}
