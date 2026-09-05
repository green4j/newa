/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.netty.buffer.ByteBuf;

/**
 * What the frames of a session are handed to, one interface per type of frame there is to hand over.
 * <p>
 * Both are optional and neither is a no-op: a session takes the types it was given a receiver for and
 * refuses the rest with a {@code 1003}, so a client which sends a type this end does not serve is told so
 * rather than left waiting for an answer to a frame which went nowhere. Ping, pong and close are not here at
 * all - the protocol handler answers them underneath.
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
     * What the text frames of a session go to. A session without one refuses text with a {@code 1003}.
     */
    interface Text {

        /**
         * A text message, or one piece of it, as the peer sent it. A piece is decoded whole: a character
         * split across two frames is put back together before it gets here, so what arrives is never a
         * broken one.
         *
         * @param session it came from.
         * @param message the text, valid for this call only - copy what outlives it.
         * @param last whether the message ends here.
         */
        void text(ClientSession session,
                  CharSequence message,
                  boolean last);
    }

    /**
     * What the binary frames of a session go to. A session without one refuses binary with a {@code 1003}.
     */
    interface Binary {

        /**
         * A binary message, or one piece of it, as the peer sent it.
         *
         * @param session it came from.
         * @param payload the bytes, valid for this call only - retain what outlives it.
         * @param last whether the message ends here.
         */
        void binary(ClientSession session,
                    ByteBuf payload,
                    boolean last);
    }

}
