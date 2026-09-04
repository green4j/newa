/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.websocket;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;

/**
 * What the frames of a session are handed to, one method per type of frame there is to hand over.
 * <p>
 * Both are optional and neither is a no-op: a receiver takes what it overrides and refuses the rest with a
 * {@code 1003}, so a client which sends a type this end does not serve is told so rather than left waiting
 * for an answer to a frame which went nowhere. Ping, pong and close are not here at all - the protocol
 * handler answers them underneath.
 * <p>
 * Called on the event loop of the session, one call per frame. What is handed over is valid for the call and
 * no longer: the decoder releases the frame the moment the call returns, so a {@link ByteBuf} which has to
 * outlive it needs a {@link ByteBuf#retain()} and a {@link CharSequence} needs a copy.
 * <p>
 * A message may arrive in pieces, which is what {@code last} is for: {@code false} says the message goes on
 * in the frames which follow, {@code true} closes it. Nothing here bounds the message as a whole - the frame
 * limit of the pipeline bounds one frame, not what several of them add up to - so a receiver which
 * assembles them owes itself a limit of its own, and a receiver which does not want them at all may end the
 * session as soon as it is handed a piece which is not the last.
 * <p>
 * Anything thrown here is reported to the observer of the session and ends it with a {@code 1011}, the way
 * {@link ClientSession#receiveFailed(Throwable)} describes.
 */
public interface Receiver {

    /**
     * A text message, or one piece of it, as the peer sent it. A piece is decoded whole: a character split
     * across two frames is put back together before it gets here, so what arrives is never a broken one.
     *
     * @param session it came from.
     * @param message the text, valid for this call only - copy what outlives it.
     * @param last whether the message ends here.
     */
    default void text(final ClientSession session,
                      final CharSequence message,
                      final boolean last) {
        session.closeWith(WebSocketCloseStatus.INVALID_MESSAGE_TYPE); // nothing here takes text, and a
        // client which sends it is told which of the two it is rather than left with a frame which went
        // nowhere
    }

    /**
     * A binary message, or one piece of it, as the peer sent it.
     *
     * @param session it came from.
     * @param payload the bytes, valid for this call only - retain what outlives it.
     * @param last whether the message ends here.
     */
    default void binary(final ClientSession session,
                        final ByteBuf payload,
                        final boolean last) {
        session.closeWith(WebSocketCloseStatus.INVALID_MESSAGE_TYPE);
    }

}
