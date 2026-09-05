/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * Told when a session opens and when it closes, once each - what the subscriptions layer is built on, and
 * where an api of your own keeps whatever it holds per session.
 * <p>
 * {@code onSessionClosed} runs on whichever thread closed the session - its own event loop when the channel
 * went away, which is how most of them end, and the caller's thread when something closed it by hand.
 */
public interface ClientSessionsListener {

    void onSessionOpened(ClientSession session);

    void onSessionClosed(ClientSession session);

}
