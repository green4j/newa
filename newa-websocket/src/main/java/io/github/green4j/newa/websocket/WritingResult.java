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

    /**
     * The frame was handed to the channel, which is not the same as having reached the peer: the write
     * future is deliberately not listened to, because a listener per frame is an allocation per frame on a
     * fan-out path. A write which then fails at the socket is found on the next {@link ClientSession#send}
     * instead, as back pressure or as a closed channel, and reported through the two methods below.
     *
     * @param session which wrote.
     */
    void onWriteSuccess(ClientSession session);

    /**
     * The channel is no longer writable, so nothing was written and the frame was released. The session is
     * behind, and what to do about it - close it, mark it lagging, let it resync - is this method's.
     *
     * @param session which could not write.
     */
    void onWriteBackPressure(ClientSession session);

    /**
     * The write could not be made at all - the channel was closed, or something on the way to it threw.
     * There is nothing left to send on, so this reports and the session goes.
     *
     * @param session which failed.
     * @param error which ended it.
     */
    void onWriteError(ClientSession session, Throwable error);

}