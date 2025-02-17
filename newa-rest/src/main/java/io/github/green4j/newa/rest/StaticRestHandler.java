package io.github.green4j.newa.rest;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;

public class StaticRestHandler implements RestHandle {
    public static StaticRestHandler json(final CharSequence content) {
        return new StaticRestHandler(APPLICATION_JSON, content);
    }

    public static StaticRestHandler txt(final CharSequence content) {
        return new StaticRestHandler(TEXT_PLAIN, content);
    }

    private final FullHttpResponseContent content;

    public StaticRestHandler(final AsciiString contentType,
                             final CharSequence content) {
        this(contentType, content.toString().getBytes());
    }

    public StaticRestHandler(final AsciiString contentType,
                             final byte[] content) {
        this.content = new DefaultFullHttpResponseContent(
                contentType,
                content,
                0,
                content.length
        );
    }

    @Override
    public void handle(final ChannelHandlerContext ctx,
                       final FullHttpRequest request,
                       final PathParameters pathParameters,
                       final Result result) {
        result.ok(content);
    }
}
