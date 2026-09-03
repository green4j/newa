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

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.github.green4j.newa.lang.CloseHelper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.List;

public class WsApiHandler extends WebSocketServerProtocolHandler {
    private final WsApi wsApi;
    private final long pingIntervalMs;
    private final long readTimeoutMs;
    private final ChannelErrorHandler channelErrorHandler;

    private ClientSession session;

    /**
     * One per channel - this handler keeps the session of its own channel, so it is neither sharable nor
     * reusable. The handshake path comes from the api, and so does what receives the frames.
     *
     * @param wsApi this handler serves.
     * @param channelErrorHandler told about channel failures, or null to say nothing.
     */
    public WsApiHandler(final WsApi wsApi,
                        final ChannelErrorHandler channelErrorHandler) {
        super(wsApi.websocketPath(), null, true);

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
                            wsApi.receiver(),
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
            session.frameReceived(frame.content().readableBytes()); // the payload as it came off
            // the wire, before it is decoded into the text below

            final String request = ((TextWebSocketFrame) frame).text();
            session.receive(request);
            // don't add to out - we consumed it
            return;
        }

        // to handle other frames (Ping, Pong, Close, etc.) as usual
        super.decode(ctx, frame, out);
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
