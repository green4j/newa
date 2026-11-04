/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.CopyingStringBuilder;
import io.github.green4j.jelly.JsonNumber;
import io.github.green4j.jelly.JsonParserListenerAdapter;
import io.github.green4j.jelly.simple.JsonValue;
import io.github.green4j.jelly.simple.JsonValueBuilder;
import io.github.green4j.jelly.simple.JsonValueParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * {@link Utf8JsonReader}: a document is read the same from every kind of buffer and stream there is - an array,
 * a direct buffer, a composite one cut at every byte, one with no NIO buffer at all - with the chars cut by
 * the parts put back together, an error is placed at the byte of the whole document, and the parser of the
 * thread is reused, except after a large document or from a listener of a read in progress.
 */
class Utf8JsonReaderTest {
    private static final String[] DOCUMENTS = {
        "{\"name\":\"va\\\"l\\u0041ue\",\"t\":true,\"f\":false,\"n\":null,"
                + "\"numbers\":[0,-12,3.25,4e2,5.5E-3],"
                + "\"nested\":[{\"a\":[[],{}]},{\"b\":{\"c\":[1,[2,[3]]]}}],\"s\":\"\"}",
        "[\"\u0436\u0435\u043b\u0435\",\"\u6570\u636e\",\"\ud83d\ude00 \u00e9 \\u0436\\ud83d\\ude00\\n\","
                + "{\"\u043a\u043b\u044e\u0447\":\"\u0437\u043d\u0430\u0447\u0435\u043d\u0438\u0435\"}]",
        "  [1, 2 , 3 ]  ",
        "\"\ud83d\ude00\"",
        "12.5",
        "true",
        "null",
        "{}"
    };

    /**
     * Every way a {@link ByteBuf} can hold the bytes, as far as reading them goes. Each puts something in
     * front of the document, so an index counted from the wrong place shows.
     */
    enum Kind {
        HEAP(bytes -> Unpooled.wrappedBuffer(withPrefix(bytes)).readerIndex(PREFIX.length)),
        HEAP_SLICE(bytes -> Unpooled.wrappedBuffer(withPrefix(bytes)).slice(PREFIX.length, bytes.length)),
        // not Unpooled.directBuffer(): under the paranoid leak detection of the tests, that buffer records the
        // stack of every access to it, and the allocation test would measure the records
        DIRECT(bytes -> new UnpooledDirectByteBuf(UnpooledByteBufAllocator.DEFAULT,
                PREFIX.length + bytes.length, PREFIX.length + bytes.length)
                .writeBytes(PREFIX).writeBytes(bytes).readerIndex(PREFIX.length)),
        COMPOSITE_OF_SINGLE_BYTES(bytes -> {
            final CompositeByteBuf composite = Unpooled.compositeBuffer(Integer.MAX_VALUE);
            composite.addComponent(true, Unpooled.directBuffer(PREFIX.length).writeBytes(PREFIX));
            for (int i = 0; i < bytes.length; i++) {
                composite.addComponent(true, Unpooled.directBuffer(1).writeByte(bytes[i]));
            }
            return composite.readerIndex(PREFIX.length);
        }),
        NO_ARRAY_NOR_NIO_BUFFER(bytes -> {
            final ByteBuf buffer = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT,
                    PREFIX.length + bytes.length, PREFIX.length + bytes.length) {
                @Override
                public boolean hasArray() {
                    return false;
                }

                @Override
                public int nioBufferCount() {
                    return -1;
                }
            };
            return buffer.writeBytes(PREFIX).writeBytes(bytes).readerIndex(PREFIX.length);
        });

        private final Function<byte[], ByteBuf> wrap;

        Kind(final Function<byte[], ByteBuf> wrap) {
            this.wrap = wrap;
        }

        boolean inParts() {
            return this == COMPOSITE_OF_SINGLE_BYTES;
        }

        ByteBuf of(final String document) {
            return wrap.apply(utf8(document));
        }
    }

    private static final byte[] PREFIX = {'x', (byte) 0xd0, (byte) 0xb6};

    @ParameterizedTest
    @EnumSource(Kind.class)
    void readsADocumentTheSameFromEveryKindOfBuffer(final Kind kind) {
        for (final String document : DOCUMENTS) {
            final ByteBuf data = kind.of(document);
            try {
                final int readerIndex = data.readerIndex();
                final int writerIndex = data.writerIndex();

                Assertions.assertEquals(expected(document), Utf8JsonReader.value(data).toString(), document);
                Assertions.assertEquals(expected(document),
                        Utf8JsonReader.parse(data, new Recorder()).value().toString(), document);

                Assertions.assertEquals(readerIndex, data.readerIndex(), "The reader index was moved");
                Assertions.assertEquals(writerIndex, data.writerIndex(), "The writer index was moved");
            } finally {
                data.release();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void decodesTheSameTextFromEveryKindOfBuffer(final Kind kind) {
        final String text = "a\u00e9\u20ac\ud83d\ude00b";
        final ByteBuf data = kind.of(text);
        try {
            final CopyingStringBuilder builder = new CopyingStringBuilder();
            builder.start();
            Assertions.assertEquals(data.readableBytes(),
                    Utf8JsonReader.append(builder, data, data.readerIndex(), data.readableBytes()));
            Assertions.assertEquals(text, builder.toString());
        } finally {
            data.release();
        }
    }

    @Test
    void decodesACharCutByTwoAppends() {
        final byte[] bytes = utf8("\ud83d\ude00");
        final ByteBuf data = Unpooled.directBuffer(bytes.length).writeBytes(bytes);
        try {
            for (int cut = 1; cut < bytes.length; cut++) {
                final CopyingStringBuilder builder = new CopyingStringBuilder();
                builder.start();
                Utf8JsonReader.append(builder, data, 0, cut);
                Utf8JsonReader.append(builder, data, cut, bytes.length - cut);
                Assertions.assertEquals("\ud83d\ude00", builder.toString(), "Cut at " + cut);
            }
        } finally {
            data.release();
        }
    }

    @Test
    void appendsNothingPastTheSliceItIsGiven() {
        final ByteBuf data = Unpooled.wrappedBuffer(utf8("abcd")).slice(0, 2);
        final CopyingStringBuilder builder = new CopyingStringBuilder();
        builder.start();
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> Utf8JsonReader.append(builder, data, 1, 2));
    }

    @ParameterizedTest(name = "{0} bytes a read")
    @CsvSource({"1", "3", "8192", "100000"})
    void readsADocumentTheSameFromAStream(final int bytesARead) throws IOException {
        for (final String document : DOCUMENTS) {
            Assertions.assertEquals(expected(document),
                    Utf8JsonReader.value(stream(document, bytesARead)).toString(), document);
            Assertions.assertEquals(expected(document),
                    Utf8JsonReader.parse(stream(document, bytesARead), new Recorder()).value().toString(),
                    document);
        }
    }

    @Test
    void readsADocumentLongerThanTheBufferOfTheStream() throws IOException {
        final StringBuilder document = new StringBuilder("[");
        for (int i = 0; i < 5_000; i++) {
            document.append(i == 0 ? "" : ",").append("\"\u00e9").append(i).append('"');
        }
        final String json = document.append(']').toString();

        Assertions.assertEquals(expected(json), Utf8JsonReader.value(stream(json, 100_000)).toString());
    }

    @Test
    void keepsANumberWiderThanALongExact() {
        final String number = "123456789012345678901234.5";
        Assertions.assertEquals(new BigDecimal(number),
                Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8("[" + number + "]")))
                        .asArrayRequired().getDecimalRequired(0));
    }

    /**
     * The last column is what a document read in parts is told: the number is a value which the end of a
     * part has cut from the byte after it, and the parser counts it from the part it ended in.
     *
     * @param document malformed.
     * @param position of the error in a document read in one part.
     * @param positionInParts of the error in a document read a byte a part.
     * @throws IOException never, the streams are in memory.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "{\"a\":[1 2]}   | 8 | 9",  // found in the middle
        "{\"a\":x}       | 5 | 5",
        "[1,             | 3 | 3",  // found by the end: the document is unfinished, so it is placed past it
        "{\"a\":\"\u00e9 | 8 | 8"
    })
    void placesAnErrorAtTheByteOfTheWholeDocument(final String document,
                                                  final int position,
                                                  final int positionInParts) throws IOException {
        for (final Kind kind : Kind.values()) {
            final String message = "at position: " + (kind.inParts() ? positionInParts : position);
            final ByteBuf data = kind.of(document);
            try {
                assertMalformed(message, () -> Utf8JsonReader.value(data), kind.name());

                final Recorder recorder = new Recorder();
                assertMalformed(message, () -> Utf8JsonReader.parse(data, recorder), kind.name());
                Assertions.assertTrue(recorder.events().endsWith("error ended"), recorder.events());
            } finally {
                data.release();
            }
        }
        assertMalformed("at position: " + positionInParts, () -> Utf8JsonReader.value(stream(document, 1)),
                "A byte a read");
        assertMalformed("at position: " + position, () -> Utf8JsonReader.value(stream(document, 8192)),
                "The whole document a read");

        Assertions.assertEquals("[1]", Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8("[1]"))).toString()
                .replaceAll("\\s", ""), "The next document was not read from scratch");
    }

    /**
     * Bytes which are not UTF-8 are refused rather than read as U+FFFD, wherever the parts of the document
     * cut them.
     *
     * @param malformed the bytes in the middle of a string, in hex.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "80",          // a continuation byte with nothing before it
        "c0 80",       // an overlong form of U+0000
        "e0 80 80",    // an overlong form of U+0000 in three bytes
        "ed a0 80",    // a surrogate
        "f4 90 80 80", // past U+10FFFF
        "f8 80 80 80", // not a leading byte of any length
        "e2 82"        // a character the string ends in the middle of
    })
    void bytesWhichAreNotUtf8AreAnError(final String malformed) throws IOException {
        final String[] hex = malformed.split(" ");
        final byte[] document = new byte[hex.length + 4];
        document[0] = '[';
        document[1] = '"';
        for (int i = 0; i < hex.length; i++) {
            document[2 + i] = (byte) Integer.parseInt(hex[i], 16);
        }
        document[hex.length + 2] = '"';
        document[hex.length + 3] = ']';

        for (final Kind kind : Kind.values()) {
            final ByteBuf data = kind.wrap.apply(document);
            try {
                Assertions.assertThrows(MalformedJsonException.class, () -> Utf8JsonReader.value(data), kind.name());
            } finally {
                data.release();
            }
        }
        for (final int bytesARead : new int[] {1, 8192}) {
            Assertions.assertThrows(MalformedJsonException.class,
                    () -> Utf8JsonReader.value(stream(document, bytesARead)), bytesARead + " bytes a read");
        }
    }

    @ParameterizedTest(name = "''{0}''")
    @CsvSource({"''", "'   '"})
    void aDocumentWithNoValueHasNone(final String document) throws IOException {
        Assertions.assertNull(Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8(document))));
        Assertions.assertNull(Utf8JsonReader.value(stream(document, 1)));
        Assertions.assertEquals("started ended",
                Utf8JsonReader.parse(Unpooled.wrappedBuffer(utf8(document)), new Recorder()).events());
    }

    @Test
    void aListenerWhichPausesTheParsingIsResumed() {
        final Recorder pausing = new Recorder() {
            @Override
            public boolean onNumberValue(final JsonNumber number) {
                super.onNumberValue(number);
                return false;
            }
        };
        Utf8JsonReader.parse(Unpooled.wrappedBuffer(utf8("[1,2,3]")), pausing);
        Assertions.assertEquals("started [ 1 2 3 ] ended", pausing.events());
    }

    @Test
    void aReadFromAListenerOfAnotherReadHasAParserOfItsOwn() {
        final StringBuilder inner = new StringBuilder();
        final Recorder outer = new Recorder() {
            @Override
            public boolean onStringValue(final CharSequence data) {
                super.onStringValue(data);
                inner.append(Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8(data.toString()))));
                return true;
            }
        };
        Utf8JsonReader.parse(Unpooled.wrappedBuffer(utf8("[\"[1]\",\"{\\\"a\\\":2}\",3]")), outer);

        Assertions.assertEquals("started [ \"[1]\" \"{\"a\":2}\" 3 ] ended", outer.events());
        Assertions.assertEquals("[1]{\"a\":2}", inner.toString().replaceAll("\\s", ""));
    }

    /**
     * A direct buffer's own view is what a read parses, and a read of another range of the very same buffer,
     * started from a listener, must not move it under the read in progress.
     */
    @Test
    void aReadFromAListenerOfAnotherRangeOfTheSameBufferLeavesTheBufferOfTheReadAlone() {
        final byte[] bytes = utf8("[1][\"a\",2,3]");
        final ByteBuf data = new UnpooledDirectByteBuf(UnpooledByteBufAllocator.DEFAULT, bytes.length, bytes.length)
                .writeBytes(bytes).readerIndex(3);
        final StringBuilder inner = new StringBuilder();
        final Recorder outer = new Recorder() {
            @Override
            public boolean onStringValue(final CharSequence value) {
                super.onStringValue(value);
                data.setIndex(0, 3);
                inner.append(Utf8JsonReader.value(data));
                data.setIndex(3, bytes.length);
                return true;
            }
        };
        try {
            Utf8JsonReader.parse(data, outer);

            Assertions.assertEquals("started [ \"a\" 2 3 ] ended", outer.events());
            Assertions.assertEquals("[1]", inner.toString().replaceAll("\\s", ""));
        } finally {
            data.release();
        }
    }

    @Test
    void aListenerWhichThrowsLeavesNothingToTheNextRead() {
        final Recorder throwing = new Recorder() {
            @Override
            public boolean onTrueValue() {
                throw new IllegalStateException("Thrown by the listener");
            }
        };
        Assertions.assertThrows(IllegalStateException.class,
                () -> Utf8JsonReader.parse(Unpooled.wrappedBuffer(utf8("{\"a\":[\"s\",true")), throwing));

        Assertions.assertEquals(expected("{\"b\":1}"),
                Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8("{\"b\":1}"))).toString());
    }

    @Test
    void theParserIsKeptByTheThreadUnlessTheDocumentWasLarge() {
        final Object parser = Utf8JsonReader.retainedParser();
        Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8("[\"small\"]")));
        Assertions.assertSame(parser, Utf8JsonReader.retainedParser());

        final String large = "[\"" + "x".repeat(Utf8JsonReader.RETAINED_DOCUMENT_BYTES) + "\"]";
        Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8(large)));
        final Object afterLarge = Utf8JsonReader.retainedParser();
        Assertions.assertNotSame(parser, afterLarge, "The parser which read a large document was kept");

        Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8("[\"small\"]")));
        Assertions.assertSame(afterLarge, Utf8JsonReader.retainedParser());
    }

    /**
     * The parser is the thread's, the tree is the caller's: once it has been handed over, nothing of the
     * thread holds it.
     *
     * @throws IOException never, the stream is in memory
     */
    @Test
    void theTreeIsNotHeldByTheThreadOnceItHasBeenHandedOver() throws IOException {
        Assertions.assertNotNull(Utf8JsonReader.value(Unpooled.wrappedBuffer(utf8("{\"a\":[1]}"))));
        Assertions.assertNull(Utf8JsonReader.retainedValue(), "The tree read from a buffer was kept");

        Assertions.assertNotNull(Utf8JsonReader.value(new ByteArrayInputStream(utf8("{\"a\":[1]}"))));
        Assertions.assertNull(Utf8JsonReader.retainedValue(), "The tree read from a stream was kept");
    }

    /**
     * What this class is for: once the thread has its parser, a document read into a listener costs no
     * allocation, whatever the buffer, and whatever the chars it holds.
     *
     * @param kind of the buffer the document is in.
     */
    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"HEAP", "HEAP_SLICE", "DIRECT"})
    void readingIntoAListenerAllocatesNothing(final Kind kind) {
        final ByteBuf data = kind.of(DOCUMENTS[1]);
        final JsonParserListenerAdapter listener = new JsonParserListenerAdapter();
        final CopyingStringBuilder text = new CopyingStringBuilder();
        try {
            final com.sun.management.ThreadMXBean threads =
                    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            final long thread = Thread.currentThread().getId();
            final int reads = 10_000;
            for (int i = 0; i < reads; i++) { // the parser of the thread and what it has grown, once
                Utf8JsonReader.parse(data, listener);
                text.start();
                Utf8JsonReader.append(text, data, data.readerIndex(), data.readableBytes());
            }

            final long before = threads.getThreadAllocatedBytes(thread);
            for (int i = 0; i < reads; i++) {
                Utf8JsonReader.parse(data, listener);
                text.start();
                Utf8JsonReader.append(text, data, data.readerIndex(), data.readableBytes());
            }
            final long allocated = threads.getThreadAllocatedBytes(thread) - before;

            Assertions.assertTrue(allocated < reads, // less than a byte a read: what measuring itself costs
                    allocated + " bytes allocated by " + reads + " reads");
        } finally {
            data.release();
        }
    }

    private static void assertMalformed(final String message,
                                        final Executable read,
                                        final String how) {
        final MalformedJsonException malformed = Assertions.assertThrows(MalformedJsonException.class, read, how);
        Assertions.assertTrue(malformed.getMessage().endsWith(message), how + ": " + malformed.getMessage());
    }

    private static String expected(final String document) {
        return new JsonValueParser().parseAndEoj(document).toString();
    }

    private static InputStream stream(final String document,
                                      final int bytesARead) {
        return stream(utf8(document), bytesARead);
    }

    private static InputStream stream(final byte[] document,
                                      final int bytesARead) {
        return new ByteArrayInputStream(document) {
            @Override
            public synchronized int read(final byte[] b, final int off, final int len) {
                return super.read(b, off, Math.min(len, bytesARead));
            }
        };
    }

    private static byte[] withPrefix(final byte[] bytes) {
        final byte[] result = new byte[PREFIX.length + bytes.length];
        System.arraycopy(PREFIX, 0, result, 0, PREFIX.length);
        System.arraycopy(bytes, 0, result, PREFIX.length, bytes.length);
        return result;
    }

    private static byte[] utf8(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes down the events of a document, and builds its tree from them, so a document read into a listener
     * can be compared with the same document read into a tree.
     */
    private static class Recorder extends JsonParserListenerAdapter {
        private final StringBuilder events = new StringBuilder();
        private final JsonValueBuilder tree = new JsonValueBuilder();

        String events() {
            return events.toString().trim();
        }

        JsonValue value() {
            return tree.value();
        }

        @Override
        public void onJsonStarted() {
            events.setLength(0);
            events.append("started");
            tree.onJsonStarted();
        }

        @Override
        public void onError(final String error, final int position) {
            events.append(" error");
            tree.onError(error, position);
        }

        @Override
        public void onJsonEnded() {
            events.append(" ended");
            tree.onJsonEnded();
        }

        @Override
        public boolean onObjectStarted() {
            events.append(" {");
            return tree.onObjectStarted();
        }

        @Override
        public boolean onObjectMember(final CharSequence name) {
            events.append(' ').append(name).append(':');
            return tree.onObjectMember(name);
        }

        @Override
        public boolean onObjectEnded() {
            events.append(" }");
            return tree.onObjectEnded();
        }

        @Override
        public boolean onArrayStarted() {
            events.append(" [");
            return tree.onArrayStarted();
        }

        @Override
        public boolean onArrayEnded() {
            events.append(" ]");
            return tree.onArrayEnded();
        }

        @Override
        public boolean onStringValue(final CharSequence data) {
            events.append(" \"").append(data).append('"');
            return tree.onStringValue(data);
        }

        @Override
        public boolean onNumberValue(final JsonNumber number) {
            events.append(' ').append(number.mantissa());
            return tree.onNumberValue(number);
        }

        @Override
        public boolean onTrueValue() {
            events.append(" true");
            return tree.onTrueValue();
        }

        @Override
        public boolean onFalseValue() {
            events.append(" false");
            return tree.onFalseValue();
        }

        @Override
        public boolean onNullValue() {
            events.append(" null");
            return tree.onNullValue();
        }
    }
}
