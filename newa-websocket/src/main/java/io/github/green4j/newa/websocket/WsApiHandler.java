/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.CloseHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.CharsetUtil;

import java.util.List;

/**
 * Completes the handshake of a {@link WsApi} and turns every frame after it into a call on the session -
 * text and binary to the receivers, ping, pong and close answered underneath by Netty.
 * <p>
 * A request whose uri is not this api's handshake path is passed on untouched, which is what lets a REST
 * handler behind this one serve the same port. One per channel; it holds the session it opened.
 * <p>
 * A fragmented message is handed over piece by piece, with {@code last} saying which piece ends it, and
 * nothing here holds the pieces: one frame is the largest buffer this pipeline holds, which is why the
 * frame limit is the only size an inbound message is bounded by. What is carried across a fragment
 * boundary is three bytes of a cut character and nothing else.
 * <p>
 * A frame of a type this api took no receiver for is answered {@code 1003} and the session ends.
 */
public class WsApiHandler extends WebSocketServerProtocolHandler {
    private final WsApi wsApi;
    private final long pingIntervalMs;
    private final long readTimeoutMs;
    private final ChannelErrorHandler channelErrorHandler;

    private ClientSession session;

    // What a continuation frame continues, said by the frame which began the message. One handler per
    // channel and every decode on its event loop, so a plain field is all this takes.
    private boolean continuingBinary;

    // The bytes of a character which a text fragment ended in the middle of, carried over to the fragment
    // which finishes it. A character is four bytes at most, so three is the most which is ever unfinished.
    private final byte[] textTail = new byte[3];
    private int textTailLength;

    /**
     * One per channel - this handler keeps the session of its own channel, so it is neither sharable nor
     * reusable. The handshake path comes from the api, and so does what receives the frames.
     *
     * <p>Frames are bounded at {@link WsServer#DEFAULT_MAX_FRAME_PAYLOAD_LENGTH} and extensions are not
     * allowed, which is what a pipeline without a {@code WebSocketServerCompressionHandler} in it means.
     * Add one and this is the wrong constructor - see the other.
     *
     * @param wsApi this handler serves.
     * @param channelErrorHandler told about channel failures, or null to say nothing.
     */
    public WsApiHandler(final WsApi wsApi,
                        final ChannelErrorHandler channelErrorHandler) {
        this(wsApi, channelErrorHandler, WsServer.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH, false);
    }

    /**
     * One per channel, with the two things about the handshake which are the pipeline's to say rather than
     * the api's.
     *
     * <p>{@code allowExtensions} is not a preference: it has to agree with what is in the pipeline. With a
     * {@code WebSocketServerCompressionHandler} present it must be true, or the first deflated frame is a
     * protocol violation; without one it should be false, or the decoder accepts frames with the reserved
     * bits set and hands their payload on as it lies, uninflated, with nothing having negotiated anything.
     * {@link WsServer} passes what {@link WsServer#withCompression()} said.
     *
     * @param wsApi this handler serves.
     * @param channelErrorHandler told about channel failures, or null to say nothing.
     * @param maxFramePayloadLength a single frame may carry; a larger one is answered with close status
     *                              1009 and the connection goes. Under a compression handler it is the
     *                              inflated frame this bounds as well - see {@link WsServer}.
     * @param allowExtensions whether the decoder accepts the reserved bits an extension negotiates.
     */
    public WsApiHandler(final WsApi wsApi,
                        final ChannelErrorHandler channelErrorHandler,
                        final int maxFramePayloadLength,
                        final boolean allowExtensions) {
        super(WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(wsApi.websocketPath())
                .maxFramePayloadLength(maxFramePayloadLength)
                .allowExtensions(allowExtensions)
                .build()); // everything else stays at Netty's default, which is the strict reading of the
        // protocol: masked frames expected, mask mismatches refused, UTF-8 validated, a violation closed,
        // the path matched whole and the handshake given ten seconds to finish

        this.wsApi = wsApi;
        this.pingIntervalMs = wsApi.pingIntervalMs();
        this.readTimeoutMs = wsApi.readTimeoutMs();
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx,
                                   final Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            session = wsApi.newSession(
                    new ClientSessionContext(
                            wsApi,
                            wsApi.textReceiver(),
                            wsApi.binaryReceiver(),
                            ctx.channel(),
                            pingIntervalMs,
                            readTimeoutMs
                    )
            );
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
        if (session != null && ctx.channel().isWritable()) {
            wsApi.writeResumed(session);
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx,
                          final WebSocketFrame frame,
                          final List<Object> out) throws Exception {
        if (session == null) {
            throw new IllegalStateException("Session is null");
        }

        session.frameArrived(); // before the type is looked at: a pong answering our ping is the only
        // frame a session which does nothing but listen ever sends, and it is what the read timeout waits
        // for. Ping, pong and close are answered by super.decode below, and reported to nobody

        if (frame instanceof TextWebSocketFrame) {
            continuingBinary = false; // whatever continues this message is text
            textTailLength = 0; // a message which begins carries nothing over from the one before it

            session.frameReceived(frame.content().readableBytes()); // the payload as it came off
            // the wire, before it is decoded into the text below

            receiveText(frame.content(), frame.isFinalFragment());
            // don't add to out - we consumed it
            return;
        }

        if (frame instanceof BinaryWebSocketFrame) {
            continuingBinary = true;

            session.frameReceived(frame.content().readableBytes());

            session.receive(frame.content(), frame.isFinalFragment()); // the buffer is the decoder's, and
            // it is released the moment this returns - what the receiver keeps, it retains
            return;
        }

        if (frame instanceof ContinuationWebSocketFrame) { // it belongs to whichever frame began the
            // message; one which began nothing is a violation the decoder has already closed with a 1002
            session.frameReceived(frame.content().readableBytes());

            if (continuingBinary) {
                session.receive(frame.content(), frame.isFinalFragment());
            } else {
                receiveText(frame.content(), frame.isFinalFragment());
            }
            return;
        }

        // to handle other frames (Ping, Pong, Close, etc.) as usual
        super.decode(ctx, frame, out);
    }

    /**
     * Hands over a text message, or a piece of one, whole characters only.
     *
     * <p>A fragment is not obliged to end on a character boundary: a multi-byte character may lie across
     * two frames, and decoding either of them on its own would put a replacement character on the seam.
     * The pipeline's UTF-8 validator says the message as a whole is valid - it keeps its state across the
     * fragments - but it does not put a cut character back together, so this does: the unfinished bytes,
     * three at most, wait here for the fragment which finishes them.
     *
     * @param content of the frame, the decoder's to release once this returns.
     * @param last whether the message ends with this frame.
     */
    private void receiveText(final ByteBuf content,
                             final boolean last) {
        ByteBuf whole = content;

        if (textTailLength > 0) { // only on a seam, and only then is anything copied
            final int carried = textTailLength;
            final byte[] combined = new byte[carried + content.readableBytes()];
            System.arraycopy(textTail, 0, combined, 0, carried);
            content.getBytes(content.readerIndex(), combined, carried, content.readableBytes());
            textTailLength = 0;
            whole = Unpooled.wrappedBuffer(combined);
        }

        final int tail = last ? 0 : unfinishedTailLength(whole); // a message which ends here has nothing
        // left to wait for: whatever is unfinished at the end of it is a broken message, and that is the
        // validator's to refuse rather than ours to hold
        if (tail > 0) {
            whole.getBytes(whole.writerIndex() - tail, textTail, 0, tail);
            textTailLength = tail;
        }

        final int length = whole.readableBytes() - tail;
        if (length == 0 && !last) {
            return; // the whole fragment was part of one character, so there is not a character to hand
            // over yet
        }

        session.receive(whole.toString(whole.readerIndex(), length, CharsetUtil.UTF_8), last);
    }

    /**
     * @param content to look at the end of.
     * @return how many bytes at the end of it belong to a character which is not complete yet, 0 when it
     *         ends on a character boundary.
     */
    private static int unfinishedTailLength(final ByteBuf content) {
        final int start = content.readerIndex();
        final int end = content.writerIndex();
        final int limit = Math.max(start, end - 4); // no character of UTF-8 is longer than four bytes

        for (int i = end - 1; i >= limit; i--) {
            final int b = content.getByte(i) & 0xFF;
            if ((b & 0xC0) == 0x80) { // a continuation byte: the character it belongs to starts earlier
                continue;
            }
            final int length = characterLength(b);
            final int have = end - i;
            return have < length ? have : 0;
        }

        return 0; // four continuation bytes in a row are not a character at all, and holding them back
        // would only delay what the validator is about to refuse
    }

    private static int characterLength(final int leading) {
        if ((leading & 0x80) == 0) {
            return 1;
        }
        if ((leading & 0xE0) == 0xC0) {
            return 2;
        }
        if ((leading & 0xF0) == 0xE0) {
            return 3;
        }
        if ((leading & 0xF8) == 0xF0) {
            return 4;
        }
        return 1; // not a leading byte at all: nothing here is worth holding back
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        if (session != null) {
            CloseHelper.closeQuiet(session);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx,
                                final Throwable cause) {
        try {
            if (channelErrorHandler != null) {
                channelErrorHandler.onError(ctx.channel(), cause);
            }
        } finally {
            ctx.close();
        }
    }
}
