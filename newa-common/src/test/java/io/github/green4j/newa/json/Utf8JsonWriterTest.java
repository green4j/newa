/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.JsonGenerator;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * {@link Utf8JsonWriter}: a document is generated compact, in UTF-8, into a buffer of the allocator exactly
 * its size if it fits the thread's array and into several if it does not, a rendering which throws leaves
 * nothing behind, and a write from the rendering of a write in progress is written apart.
 */
class Utf8JsonWriterTest {
    private static final String TEXT = "\u0436\u0435\u043b\u0435 \u6570\u636e \ud83d\ude00";

    private static void renderTheDocument(final String text,
                                          final JsonGenerator output) {
        output.startObject();
        output.objectMember("text");
        output.stringValue(text);
        output.objectMember("numbers");
        output.startArray();
        output.numberValue(-12);
        output.numberValue(325, -2);
        output.endArray();
        output.endObject();
    }

    private static String expected(final String text) {
        return "{\"text\":\"" + text + "\",\"numbers\":[-12,3.25]}";
    }

    private static String utf8(final ByteBuf document) {
        try {
            return document.toString(StandardCharsets.UTF_8);
        } finally {
            document.release();
        }
    }

    @Test
    void writesTheDocumentCompactInUtf8() {
        Assertions.assertEquals(expected(TEXT), utf8(Utf8JsonWriter.write(
                UnpooledByteBufAllocator.DEFAULT, TEXT, Utf8JsonWriterTest::renderTheDocument)));
    }

    @Test
    void writesTheDocumentOfARenderingWithNoSource() {
        Assertions.assertEquals(expected("a"), utf8(Utf8JsonWriter.write(
                UnpooledByteBufAllocator.DEFAULT, output -> renderTheDocument("a", output))));
    }

    @Test
    void theBufferIsExactlyTheDocument() {
        final ByteBuf document = Utf8JsonWriter.write(
                UnpooledByteBufAllocator.DEFAULT, TEXT, Utf8JsonWriterTest::renderTheDocument);
        try {
            Assertions.assertEquals(expected(TEXT).getBytes(StandardCharsets.UTF_8).length,
                    document.capacity(), "The buffer is not the size of the document");
            Assertions.assertEquals(document.capacity(), document.readableBytes());
        } finally {
            document.release();
        }
    }

    @Test
    void aRenderingWhichThrowsLeavesNothingBehind() {
        final int[] allocated = new int[1];
        final AbstractByteBufAllocator counting = new AbstractByteBufAllocator(false) {
            @Override
            protected ByteBuf newHeapBuffer(final int initialCapacity, final int maxCapacity) {
                allocated[0]++;
                return new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            }

            @Override
            protected ByteBuf newDirectBuffer(final int initialCapacity, final int maxCapacity) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isDirectBufferPooled() {
                return false;
            }
        };

        final IllegalStateException failed = Assertions.assertThrows(IllegalStateException.class,
                () -> Utf8JsonWriter.write(counting, output -> {
                    output.startArray();
                    throw new IllegalStateException("Not today");
                }));
        Assertions.assertEquals("Not today", failed.getMessage());
        Assertions.assertEquals(0, allocated[0], "A rendering which threw took a buffer from the allocator");

        Assertions.assertEquals(expected(TEXT), utf8(Utf8JsonWriter.write(
                counting, TEXT, Utf8JsonWriterTest::renderTheDocument)),
                "A rendering which threw left something to the next write");
        Assertions.assertEquals(1, allocated[0]);
    }

    @Test
    void aWriteFromTheRenderingOfAnotherWriteHasAGeneratorOfItsOwn() {
        final String document = utf8(Utf8JsonWriter.write(UnpooledByteBufAllocator.DEFAULT, output -> {
            output.startArray();
            output.stringValue(utf8(Utf8JsonWriter.write(UnpooledByteBufAllocator.DEFAULT, TEXT,
                    Utf8JsonWriterTest::renderTheDocument)), true);
            output.numberValue(1);
            output.endArray();
        }));

        Assertions.assertEquals("[\"" + expected(TEXT).replace("\"", "\\\"") + "\",1]", document);
    }

    @Test
    void aDocumentLargerThanTheArrayOfTheThreadIsWrittenWhole() {
        final String large = (TEXT + "x".repeat(1000)).repeat(1000); // megabytes, Cyrillic across the buffers
        Assertions.assertEquals(expected(large), utf8(Utf8JsonWriter.write(
                UnpooledByteBufAllocator.DEFAULT, large, Utf8JsonWriterTest::renderTheDocument)));
    }
}
