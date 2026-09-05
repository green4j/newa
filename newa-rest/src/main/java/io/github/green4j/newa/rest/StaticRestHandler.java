/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpHeaderValues.APPLICATION_JSON;
import static io.netty.handler.codec.http.HttpHeaderValues.TEXT_PLAIN;

/**
 * Answers the same bytes to every request - a version string, a fixed document, a health check. The content
 * is rendered once, when the handler is built, and every response is that buffer.
 */
public class StaticRestHandler implements RestHandle {
    /**
     * JSON rendered as UTF-8, which is the encoding RFC 8259 requires of JSON on the wire.
     *
     * @param content to render
     * @return handler responding with the rendered content
     */
    public static StaticRestHandler json(final CharSequence content) {
        return json(content, Charset.UTF8);
    }

    /**
     * JSON rendered as {@code charset}, which is named in the {@code Content-Type} of the response.
     *
     * @param content to render
     * @param charset to render the content with
     * @return handler responding with the rendered content
     */
    public static StaticRestHandler json(final CharSequence content,
                                         final Charset charset) {
        return new StaticRestHandler(
                charset.toContentType(APPLICATION_JSON),
                encode(content, charset)
        );
    }

    /**
     * Plain text rendered as ASCII. A character outside ASCII is replaced with {@code '?'} rather than
     * reported - pass {@link Charset#UTF8} to {@link #txt(CharSequence, Charset)} to keep it.
     *
     * @param content to render
     * @return handler responding with the rendered content
     */
    public static StaticRestHandler txt(final CharSequence content) {
        return txt(content, Charset.US_ASCII);
    }

    /**
     * Plain text rendered as {@code charset}, which is named in the {@code Content-Type} of the response.
     *
     * @param content to render
     * @param charset to render the content with
     * @return handler responding with the rendered content
     */
    public static StaticRestHandler txt(final CharSequence content,
                                        final Charset charset) {
        return new StaticRestHandler(
                charset.toContentType(TEXT_PLAIN),
                encode(content, charset)
        );
    }

    private static byte[] encode(final CharSequence content,
                                 final Charset charset) {
        return content.toString().getBytes(charset.javaCharset());
    }

    private final FullHttpResponseContent content;

    /**
     * Responds with {@code content} as it is. Text has to be encoded by the caller, so that the encoding
     * of the response is never the default of whichever machine happens to be running the server - see
     * {@link #json(CharSequence, Charset)} and {@link #txt(CharSequence, Charset)}.
     *
     * @param contentType of the response
     * @param content of the response
     */
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
    public void handle(final RestContext context,
                       final Result result) {
        result.ok(content);
    }
}
