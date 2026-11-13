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
 * Wraps a {@link JsonRestHandle} into the handler an api is built from. This is what
 * {@code RestApiBuilder.getJson(...)} registers.
 * <p>
 * The document is written through the thread's {@link ByteBufWriter} into buffers of the channel's allocator
 * and sent as it is: on the heap a document costs the thread's array of at most 256 KiB, whatever its size, and
 * its bytes are in the allocator's memory - direct memory with the default one - until they are written.
 */
public class JsonRestHandler extends AbstractApplicationJsonHandler implements RestHandle {
    private final JsonRestHandle handle;

    public JsonRestHandler(final JsonRestHandle handle) {
        this.handle = handle;
    }

    public JsonRestHandler(final Charset responseCharset,
                           final JsonRestHandle handle) {
        super(responseCharset);
        this.handle = handle;
    }

    @Override
    public final void handle(final RestContext context,
                             final Result result) {
        final ByteBufWriter writer = ByteBufWriter.start(responseCharset, context.channel().alloc());
        final ByteBuf document;
        try {
            handle.doHandle(context, writer.json());
            document = writer.finish();
        } catch (final Exception e) {
            writer.abort(); // before the error response, which may need the memory they hold
            result.error(e);
            return;
        } finally {
            // whatever was thrown, Errors included, the buffers taken so far go back; nothing once finished
            writer.abort();
        }
        result.ok(contentType, document); // the document goes to the channel as it is
    }
}
