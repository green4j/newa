/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.lang.Charset;
import io.github.green4j.newa.text.ByteBufWriter;
import io.netty.buffer.ByteBuf;

/**
 * Wraps a {@link TxtRestHandle} into the handler an api is built from. This is what
 * {@code RestApiBuilder.getTxt(...)} registers.
 * <p>
 * The text is written the way {@link JsonRestHandler} writes a document: through the thread's
 * {@link ByteBufWriter} into buffers of the channel's allocator, sent as they are.
 */
public class TxtRestHandler extends AbstractTextPlainHandler implements RestHandle {
    private final TxtRestHandle handle;

    public TxtRestHandler(final TxtRestHandle handle) {
        this.handle = handle;
    }

    public TxtRestHandler(final Charset responseCharset,
                          final TxtRestHandle handle) {
        super(responseCharset);
        this.handle = handle;
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        final ByteBufWriter writer = ByteBufWriter.start(responseCharset, context.channel().alloc());
        final ByteBuf text;
        try {
            handle.doHandle(context, writer.lines());
            text = writer.finish();
        } catch (final Exception e) {
            writer.abort(); // before the error response, which may need the memory they hold
            result.error(e);
            return;
        } finally {
            // whatever was thrown, Errors included, the buffers taken so far go back; nothing once finished
            writer.abort();
        }
        result.ok(contentType, text); // the text goes to the channel as it is
    }
}
