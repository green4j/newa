/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.util.AttributeKey;

/**
 * Puts a text message which arrives in pieces back together, up to a limit, and hands the receiver it wraps
 * whole messages only: every call it makes is a {@code last} one. For a protocol of commands - a JSON
 * document a message - which has no use for half of one.
 * <p>
 * A message which comes in one frame, which is nearly every message, is handed on as it came: the same
 * text, nothing copied, nothing allocated, one look at the channel to see that no message is being put
 * together on it. Only a message in pieces is copied, piece by piece, into a buffer of its session which goes
 * once the message has been handed on.
 * <p>
 * A message longer than the limit, in one frame or in several, is answered with a {@code 1009} and the
 * session ends; what came of it is dropped, and so are the pieces still to come before the close does.
 */
public final class WholeTextReceiver implements Receiver.Text {
    private static final AttributeKey<StringBuilder> PIECES =
            AttributeKey.valueOf(WholeTextReceiver.class, "pieces");

    // what the pieces of a refused message go to until it ends
    private static final StringBuilder REFUSED = new StringBuilder(0);

    private final int maxMessageLength;
    private final Receiver.Text receiver;

    /**
     * @param maxMessageLength chars a message may add up to.
     * @param receiver told about whole messages only.
     */
    public WholeTextReceiver(final int maxMessageLength,
                             final Receiver.Text receiver) {
        if (maxMessageLength < 1) {
            throw new IllegalArgumentException(
                    "A maximum message length must be positive: " + maxMessageLength);
        }
        if (receiver == null) {
            throw new IllegalArgumentException("A receiver of whole messages is required");
        }
        this.maxMessageLength = maxMessageLength;
        this.receiver = receiver;
    }

    @Override
    public void text(final ClientSession session,
                     final CharSequence message,
                     final boolean last) {
        final Channel channel = session.channel();
        // hasAttr() looks without creating: a session which never sends a message in pieces never gets one
        final StringBuilder pieces = channel.hasAttr(PIECES) ? channel.attr(PIECES).get() : null;

        if (pieces == null) {
            if (message.length() > maxMessageLength) {
                refuse(session, last);
                return;
            }
            if (last) {
                receiver.text(session, message, true);
                return;
            }
            channel.attr(PIECES).set(new StringBuilder(message));
            return;
        }

        if (pieces == REFUSED) {
            if (last) {
                channel.attr(PIECES).set(null);
            }
            return;
        }

        if (message.length() > maxMessageLength - pieces.length()) { // the sum of the two may overflow
            refuse(session, last);
            return;
        }
        pieces.append(message);
        if (!last) {
            return;
        }
        channel.attr(PIECES).set(null); // before the call: a receiver which throws leaves nothing behind
        receiver.text(session, pieces, true);
    }

    private static void refuse(final ClientSession session,
                               final boolean last) {
        session.channel().attr(PIECES).set(last ? null : REFUSED);
        session.closeWith(WebSocketCloseStatus.MESSAGE_TOO_BIG);
    }
}
