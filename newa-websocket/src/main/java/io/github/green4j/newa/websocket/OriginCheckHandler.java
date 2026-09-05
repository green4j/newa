/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */


package io.github.green4j.newa.websocket;

import io.github.green4j.newa.lang.ChannelErrorHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

/**
 * Refuses a handshake whose {@code Origin} the {@link OriginPolicy} does not allow, before Netty's
 * handshake handler ever sees the request.
 * <p>
 * It is a handler of its own rather than a check inside {@link WsApiHandler} because there is no room
 * inside one: {@code WebSocketServerProtocolHandler} inserts Netty's handshake handler <b>in front of</b>
 * itself, so the request is consumed and answered before {@code WsApiHandler} is reached. The check has to
 * stand ahead of both, which is where {@link WsServer} puts this - right behind the aggregator, so a
 * refusal costs no more than the request already cost. A pipeline assembled by hand adds it itself, and
 * gets no check at all until it does.
 * <p>
 * Only the handshake is judged: the uri has to be the api's path, matched the way Netty matches it, and the
 * request has to ask for the upgrade. Everything else is passed on untouched, so a rest api sharing this
 * port through {@link WsServer#withHandler} answers as it always did. Once a handshake is let through this
 * handler takes itself out of the pipeline - there is exactly one per connection, and no frame should pay
 * for a check that is over.
 * <p>
 * A refusal is answered {@code 403} and the connection closed, and a {@link ForbiddenOriginException} goes
 * to the {@link ChannelErrorHandler}. The answer is given, rather than the connection dropped in the way
 * {@link HandshakeOnlyHandler} drops one, because this request <b>was</b> a handshake: whoever sent it is a
 * client of this server which needs to be told, not a scanner which needs nothing.
 */
public class OriginCheckHandler extends ChannelInboundHandlerAdapter {
    private final String websocketPath;
    private final OriginPolicy originPolicy;
    private final ChannelErrorHandler channelErrorHandler;

    /**
     * One per channel - it takes itself out of the pipeline once a handshake has passed, so it is neither
     * sharable nor reusable.
     *
     * @param websocketPath       of the api this guards, from {@link WsApi#websocketPath()}.
     * @param originPolicy        which origins may go on, never null.
     * @param channelErrorHandler told about a refusal, or null to say nothing.
     */
    public OriginCheckHandler(final String websocketPath,
                              final OriginPolicy originPolicy,
                              final ChannelErrorHandler channelErrorHandler) {
        this.websocketPath = websocketPath;
        this.originPolicy = originPolicy;
        this.channelErrorHandler = channelErrorHandler;
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx,
                            final Object msg) throws Exception {
        if (!(msg instanceof HttpRequest)) {
            super.channelRead(ctx, msg);
            return;
        }

        final HttpRequest request = (HttpRequest) msg;
        if (!isHandshake(request)) {
            super.channelRead(ctx, msg); // not ours to judge: a rest api behind us may still want it
            return;
        }

        final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (!originPolicy.accepts(origin, request.headers().get(HttpHeaderNames.HOST))) {
            refuse(ctx, msg, origin);
            return;
        }

        super.channelRead(ctx, msg);

        // the one handshake of this connection has gone through: nothing after it is an HttpRequest, and
        // every frame would pay for the two checks above on its way past
        ctx.pipeline().remove(this);
    }

    private boolean isHandshake(final HttpRequest request) {
        // the rule WebSocketServerProtocolHandshakeHandler applies with checkStartsWith off: the whole uri,
        // query string included, has to be the path. Matching more loosely here would leave a request the
        // handshake handler does not take to be refused by us and answered by nobody
        if (!request.uri().equals(websocketPath)) {
            return false;
        }
        return AsciiString.contentEqualsIgnoreCase(
                HttpHeaderValues.WEBSOCKET,
                request.headers().get(HttpHeaderNames.UPGRADE)
        );
    }

    private void refuse(final ChannelHandlerContext ctx,
                        final Object msg,
                        final String origin) {
        ReferenceCountUtil.release(msg);

        try {
            if (channelErrorHandler != null) {
                channelErrorHandler.onError(ctx.channel(), new ForbiddenOriginException(origin));
            }
        } finally {
            // the report is the handler's business and the connection is ours: one which throws does not
            // get to leave the socket open
            final FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.FORBIDDEN,
                    Unpooled.EMPTY_BUFFER
            );
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
