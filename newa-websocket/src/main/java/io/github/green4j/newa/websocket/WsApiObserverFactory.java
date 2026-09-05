/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * Produces the {@link WsApiObserver} which observes one session. Asked once per session, right after the
 * handshake and before anything else about it is known.
 * <p>
 * How much that costs is yours to decide. Return a new observer per session and every stage of that session
 * has somewhere private to keep what it copied; return one shared instance and nothing is allocated at all,
 * at the price of having to work out for yourself which session a call belongs to - across channels and
 * across threads. Return null and the session is not observed at all: not even the clock is read for it.
 * <p>
 * Called from event loop threads, so it must not block, and must be safe to call from several at once.
 */
@FunctionalInterface
public interface WsApiObserverFactory {
    /**
     * @return an observer for the session just opened, or null to observe it not at all
     */
    WsApiObserver newObserver();
}
