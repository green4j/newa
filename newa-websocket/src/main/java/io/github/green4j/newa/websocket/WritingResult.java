/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

/**
 * Told how every write of every session went - the one place back pressure and a failed write are reported
 * from, and where the decision to close a session or mark it lagging is made. {@link WsApi} implements it;
 * a session assembled by hand is given one through its {@link ClientSessionContext}.
 * <p>
 * Called on the thread which wrote, which for a fan-out is the publishing thread rather than the session's
 * own event loop.
 */
public interface WritingResult {

    void onWriteSuccess(ClientSession session);

    void onWriteBackPressure(ClientSession session);

    void onWriteError(ClientSession session, Throwable error);

}