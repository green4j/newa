/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.ByteSequence;
import io.github.green4j.jelly.CopyingStringBuilder;
import io.github.green4j.jelly.JsonParser;
import io.github.green4j.jelly.JsonParserListener;
import io.github.green4j.jelly.JsonUtf8StringBuilder;
import io.github.green4j.jelly.Utf8JsonParser;
import io.github.green4j.jelly.simple.JsonValue;
import io.github.green4j.jelly.simple.JsonValueBuilder;
import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Reads a JSON document encoded in UTF-8 right from the bytes it came in - a {@link ByteBuf} or an
 * {@link InputStream} - with green-jelly's {@link Utf8JsonParser}. The bytes are neither copied nor decoded
 * into a {@link String} of the whole document first: the syntax is read as bytes, and only string values are
 * decoded, into a buffer the parser reuses.
 * <p>
 * Each method reads one whole document, either into a {@link JsonValue} tree or into a listener of the
 * caller's, which is the way to read a document with no tree allocated at all. A listener which pauses the
 * parsing is resumed at once: the document is read to its end either way.
 * <p>
 * The parser, and what it decodes strings into, is one per thread and reused document after document. A
 * document longer than {@link #RETAINED_DOCUMENT_BYTES} is read the same way, but the parser it grew is not
 * kept, so one large document does not leave every thread which ever read one holding a buffer that size.
 * A read started from a listener of another read on the same thread gets a parser of its own.
 * <p>
 * Malformed bytes of UTF-8 are an error of the document rather than U+FFFD: an overlong form, a surrogate, a
 * code point past U+10FFFF and a character the document ends in the middle of are all refused.
 * <p>
 * A malformed document is a {@link MalformedJsonException} with the error and its position: the index of
 * the byte it was found at, counted from the first byte of the document. An unfinished document is placed at
 * its end. A value cut in two by the end of a part of a document which comes in parts - a buffer made of
 * several, or a stream - may be placed up to its own length later than the byte it starts at, because the
 * parser counts it from the part it ends in.
 */
public final class Utf8JsonReader {
    /**
     * The longest document whose parser is kept by its thread for the next one.
     */
    public static final int RETAINED_DOCUMENT_BYTES = 64 * 1024;

    private static final int STREAM_BUFFER_SIZE = 8 * 1024;

    private static final byte[] NO_BYTES = new byte[0];

    private static final ThreadLocal<Reader> READER = ThreadLocal.withInitial(Reader::new);

    private Utf8JsonReader() {
    }

    /**
     * Reads the readable bytes of the buffer as one document. Neither index of the buffer is moved.
     *
     * @param data the document
     * @return the value of the document, or {@code null} if it has no value: no bytes or only whitespace
     * @throws MalformedJsonException if the document is malformed, with the error and its position counted
     *                                 from the reader index
     */
    public static JsonValue value(final ByteBuf data) {
        final Reader reader = acquire();
        try {
            reader.read(data, reader.builder);
            return reader.takeValue();
        } finally {
            release(reader, data.readableBytes());
        }
    }

    /**
     * Reads the readable bytes of the buffer as one document, into the listener given. Neither index of the
     * buffer is moved. The listener is told about an error before it is thrown here, and it is told the
     * document has ended in either case.
     *
     * @param data the document
     * @param listener the listener
     * @param <L> the type of the listener
     * @return the listener
     * @throws MalformedJsonException if the document is malformed, with the error and its position counted
     *                                 from the reader index
     */
    public static <L extends JsonParserListener> L parse(final ByteBuf data,
                                                         final L listener) {
        final Reader reader = acquire();
        try {
            reader.read(data, listener);
            return listener;
        } finally {
            release(reader, data.readableBytes());
        }
    }

    /**
     * Reads the stream up to its end as one document. The stream is not closed. Nothing more is read from
     * it once an error has been found.
     *
     * @param data the document
     * @return the value of the document, or {@code null} if it has no value: no bytes or only whitespace
     * @throws IOException if the stream fails
     * @throws MalformedJsonException if the document is malformed, with the error and its position
     */
    public static JsonValue value(final InputStream data) throws IOException {
        final Reader reader = acquire();
        try {
            reader.read(data, reader.builder);
            return reader.takeValue();
        } finally {
            release(reader, reader.streamed);
        }
    }

    /**
     * Reads the stream up to its end as one document, into the listener given. The stream is not closed.
     * Nothing more is read from it once an error has been found. The listener is told about an error before
     * it is thrown here, and it is told the document has ended in either case. A failure of the stream ends
     * nothing: the exception is all there is.
     *
     * @param data the document
     * @param listener the listener
     * @param <L> the type of the listener
     * @return the listener
     * @throws IOException if the stream fails
     * @throws MalformedJsonException if the document is malformed, with the error and its position
     */
    public static <L extends JsonParserListener> L parse(final InputStream data,
                                                         final L listener) throws IOException {
        final Reader reader = acquire();
        try {
            reader.read(data, listener);
            return listener;
        } finally {
            release(reader, reader.streamed);
        }
    }

    /**
     * Decodes UTF-8 bytes of the buffer into the builder, at the indexes given: neither index of the buffer
     * is used or moved. A char cut by the end of the bytes is kept by the builder for the bytes it is appended
     * next, so a text which comes in parts is decoded part by part.
     *
     * @param builder what the chars go to
     * @param data the bytes
     * @param index the index of the first byte
     * @param length the number of the bytes
     * @return the number of the bytes appended, less than {@code length} only if the builder refuses
     *         malformed bytes and has found some
     * @throws IndexOutOfBoundsException if the bytes are not all within the capacity of the buffer
     */
    public static int append(final JsonUtf8StringBuilder builder,
                             final ByteBuf data,
                             final int index,
                             final int length) {
        Objects.checkFromIndexSize(index, length, data.capacity()); // the array of a slice goes on past it
        if (data.hasArray()) {
            return builder.append(data.array(), data.arrayOffset() + index, length);
        }
        final int count = data.nioBufferCount();
        if (count == 1) {
            final ByteBuffer part = data.internalNioBuffer(index, length); // no view allocated
            return builder.append(part, part.position(), part.remaining());
        }
        if (count > 1) {
            final ByteBuffer[] parts = data.nioBuffers(index, length);
            int appended = 0;
            for (int i = 0; i < parts.length; i++) {
                final ByteBuffer part = parts[i];
                final int partLength = part.remaining();
                final int partAppended = builder.append(part, part.position(), partLength);
                appended += partAppended;
                if (partAppended != partLength) {
                    break;
                }
            }
            return appended;
        }
        return builder.append(new ByteBufSequence(data, index, length), 0, length);
    }

    // what the next read on this thread is parsed with, for the tests to see whether it was kept
    static Object retainedParser() {
        return READER.get().parser;
    }

    // the tree the thread still holds after a read, for the tests to see that it holds none
    static Object retainedValue() {
        return READER.get().builder.value();
    }

    private static Reader acquire() {
        final Reader reader = READER.get();
        if (reader.busy) {
            return new Reader(); // a read from a listener of the read in progress
        }
        reader.busy = true;
        return reader;
    }

    private static void release(final Reader reader,
                                final long bytes) {
        reader.parser.setListener(null); // the caller's listener is not kept by the thread
        if (reader == READER.get()) {
            reader.busy = false;
            if (bytes > RETAINED_DOCUMENT_BYTES) {
                READER.remove(); // the buffers it has grown go with it
            }
        }
    }

    private static final class Reader {
        private final Utf8JsonParser parser = new Utf8JsonParser(new CopyingStringBuilder(false, true));
        private final JsonValueBuilder builder = new JsonValueBuilder(parser);

        private byte[] streamBuffer;

        private boolean busy;

        // the index of the part being parsed, which the parser counts the position of an error from, and
        // past the last part the length of the document
        private long partIndex;
        private long streamed;

        void read(final ByteBuf data,
                  final JsonParserListener listener) {
            start(listener);
            final int index = data.readerIndex();
            final int length = data.readableBytes();
            if (data.hasArray()) {
                parsePart(parser.parse(data.array(), data.arrayOffset() + index, length), length);
            } else {
                final int count = data.nioBufferCount();
                if (count == 1) {
                    // the buffer's own view allocates nothing. A read started from a listener of this one is
                    // not busy, and takes a view of its own rather than move the one this read is parsing
                    parsePart(parser.parse(busy
                            ? data.internalNioBuffer(index, length)
                            : data.nioBuffer(index, length)), length);
                } else if (count > 1) {
                    final ByteBuffer[] parts = data.nioBuffers(index, length);
                    for (int i = 0; i < parts.length && !parser.hasError(); i++) {
                        final ByteBuffer part = parts[i];
                        parsePart(parser.parse(part), part.remaining());
                    }
                } else {
                    parsePart(parser.parse(new ByteBufSequence(data, index, length)), length);
                }
            }
            end();
        }

        void read(final InputStream data,
                  final JsonParserListener listener) throws IOException {
            start(listener);
            byte[] buffer = streamBuffer;
            if (buffer == null) {
                buffer = new byte[STREAM_BUFFER_SIZE];
                streamBuffer = buffer;
            }
            int read;
            while ((read = data.read(buffer, 0, buffer.length)) != -1) {
                streamed += read;
                parsePart(parser.parse(buffer, 0, read), read);
                if (parser.hasError()) {
                    break;
                }
            }
            end();
        }

        // the tree is the caller's: the builder, which the thread keeps, would hold it until the next read,
        // and a tree of a request body has no business living on in the thread which served the request
        JsonValue takeValue() {
            final JsonValue value = builder.value();
            builder.onJsonStarted(); // the one way to make the builder let go of what it has built
            return value;
        }

        private void start(final JsonParserListener listener) {
            partIndex = 0;
            streamed = 0;
            parser.setListener(listener);
            // whatever the last read left - a listener may have thrown out of the middle of it - is dropped,
            // and the empty part tells the listener a document has started even if no byte follows
            parser.reset();
            parser.parse(NO_BYTES);
        }

        private void end() {
            final boolean failedBefore = parser.hasError();
            parser.eoj();
            if (parser.hasError()) {
                // an error found by eoj() is at the end of the document, past the last part
                throw new MalformedJsonException("An error '" + parser.getError() + "' at position: "
                        + (failedBefore ? partIndex + parser.getErrorPosition() : partIndex));
            }
        }

        private void parsePart(final JsonParser.Next paused,
                               final int length) {
            JsonParser.Next next = paused;
            while (next != null) {
                next = next.next();
            }
            if (!parser.hasError()) {
                partIndex += length;
            }
        }
    }

    // for the buffer which can't be read as either an array or a ByteBuffer
    private static final class ByteBufSequence implements ByteSequence {
        private final ByteBuf data;
        private final int index;
        private final int length;

        ByteBufSequence(final ByteBuf data,
                        final int index,
                        final int length) {
            this.data = data;
            this.index = index;
            this.length = length;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public byte byteAt(final int i) {
            return data.getByte(index + i);
        }
    }
}
