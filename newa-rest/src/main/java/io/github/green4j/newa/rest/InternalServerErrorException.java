package io.github.green4j.newa.rest;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AsciiString;

import java.nio.charset.StandardCharsets;

public class InternalServerErrorException extends RestException {
    static final long serialVersionUID = -2387516993124229947L;

    private final AsciiString contentType;
    private final byte[] content;
    private final int offset;
    private final int length;

    public InternalServerErrorException(final AsciiString contentType,
                                        final String content) {
        this.contentType = contentType;
        this.content = content.getBytes(StandardCharsets.US_ASCII);
        this.offset = 0;
        this.length = this.content.length;
    }

    public InternalServerErrorException(final AsciiString contentType,
                                        final byte[] content,
                                        final int offset,
                                        final int length) {
        this.contentType = contentType;
        this.content = content;
        this.offset = offset;
        this.length = length;
    }

    public InternalServerErrorException(final AsciiString contentType,
                                        final CharSequence charSequence) {
        this(contentType, charSequence.toString());
    }

    public void set(final FullHttpResponse response) {
        response.setContent(contentType, content, offset, length);
    }

    @Override
    public HttpResponseStatus status() {
        return HttpResponseStatus.INTERNAL_SERVER_ERROR;
    }
}
