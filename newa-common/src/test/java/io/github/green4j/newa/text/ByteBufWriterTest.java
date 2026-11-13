/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.text;

import io.github.green4j.jelly.AsciiByteArrayWriter;
import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.jelly.Utf8ByteArrayWriter;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

class ByteBufWriterTest {
    private static final int SMALL = 64; // a text goes into the allocator's buffers many times over

    @ParameterizedTest
    @EnumSource(Charset.class)
    void writesTheSameBytesAsTheArrayGeneratorAcrossManyBuffers(final Charset charset) {
        final Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            final String text = text(random, random.nextInt(500));
            final long number = random.nextLong();
            final Consumer<JsonGenerator> document = out -> {
                out.startObject();
                out.objectMember("text");
                out.stringValue(text, true);
                out.objectMember("number");
                out.numberValue(number);
                out.objectMember("chars");
                out.stringValue(text.substring(0, text.length() / 2));
                out.endObject();
            };
            Assertions.assertArrayEquals(viaArray(charset, document), viaByteBuf(charset, document),
                    "round " + round);
        }
    }

    @Test
    void aTextWhichFitsTheArrayIsOneBufferExactlyItsSize() {
        final ByteBufWriter writer = small().begin(UnpooledByteBufAllocator.DEFAULT);
        writer.lines().append("\u0436\u0435\u043b\u0435");
        final ByteBuf text = writer.finish();
        try {
            Assertions.assertFalse(text instanceof CompositeByteBuf);
            Assertions.assertEquals(8, text.capacity());
            Assertions.assertEquals("\u0436\u0435\u043b\u0435", text.toString(StandardCharsets.UTF_8));
        } finally {
            text.release();
        }
    }

    @Test
    void nothingWrittenIsAnEmptyBuffer() {
        final ByteBufWriter writer = small().begin(UnpooledByteBufAllocator.DEFAULT);
        Assertions.assertEquals(0, writer.finish().readableBytes());
    }

    @Test
    void aLargerTextIsOwnedByTheCallerAndReleasesItsBuffers() {
        final List<ByteBuf> taken = new ArrayList<>();
        final ByteBufWriter writer = small().begin(tracking(taken, -1));
        final JsonGenerator out = writer.json();
        out.startArray();
        for (int i = 0; i < 100; i++) {
            out.stringValue("x".repeat(8)); // 10 bytes and a comma
        }
        // the array is left open: finish() closes it
        Assertions.assertEquals(1 + 100 * 10 + 99, writer.length());
        final ByteBuf document = writer.finish();

        Assertions.assertEquals(1, document.refCnt());
        Assertions.assertEquals(2 + 100 * 10 + 99, document.readableBytes());
        Assertions.assertTrue(taken.size() > 10);
        document.release();
        taken.forEach(buffer -> Assertions.assertEquals(0, buffer.refCnt()));
    }

    @Test
    void anAbortedTextGivesItsBuffersBackAndTheWriterWritesTheNextOne() {
        final List<ByteBuf> taken = new ArrayList<>();
        final ByteBufWriter writer = small().begin(tracking(taken, -1));
        final JsonGenerator out = writer.json();
        out.startArray();
        for (int i = 0; i < 100; i++) {
            out.stringValue("y".repeat(8)); // and then the rendering fails
        }
        writer.abort();

        Assertions.assertFalse(taken.isEmpty());
        taken.forEach(buffer -> Assertions.assertEquals(0, buffer.refCnt()));

        writer.begin(UnpooledByteBufAllocator.DEFAULT).json().numberValue(42);
        Assertions.assertEquals("42", utf8(writer.finish()));
    }

    @Test
    void anAllocationWhichFailsLeavesNothingBehind() {
        final List<ByteBuf> taken = new ArrayList<>();
        final ByteBufWriter writer = small().begin(tracking(taken, 4));
        try {
            Assertions.assertThrows(OutOfMemoryError.class, () -> {
                for (int i = 0; i < 100; i++) {
                    writer.lines().appendln("z".repeat(20));
                }
            });
        } finally {
            writer.abort();
        }

        Assertions.assertEquals(3, taken.size()); // two buffers and the composite holding them
        taken.forEach(buffer -> Assertions.assertEquals(0, buffer.refCnt()));
    }

    @Test
    void abortAfterFinishLeavesTheTextToItsOwner() {
        final ByteBufWriter writer = small().begin(UnpooledByteBufAllocator.DEFAULT);
        writer.lines().append("kept");
        final ByteBuf text = writer.finish();
        writer.abort();
        Assertions.assertEquals("kept", utf8(text));
    }

    @ParameterizedTest
    @EnumSource(Charset.class)
    void theArrayGrowsWithTheTextsUpToItsMaximumAndTheTextIsTheSame(final Charset charset) {
        final List<ByteBuf> taken = new ArrayList<>();
        final ByteBufWriter writer = new ByteBufWriter(charset, 16, 256).begin(tracking(taken, -1));
        writer.lines().append("v".repeat(1000));
        final ByteBuf text = writer.finish();

        Assertions.assertEquals("v".repeat(1000), utf8(text));
        Assertions.assertEquals(256, writer.output().array().length);
        // 16, 32, 64 and 128 as the array grew, 256 at a time from there, and what was left at the end
        Assertions.assertEquals(List.of(16, 32, 64, 128, 256, 256, 248), capacitiesOf(taken));

        // and a text which fits the grown array is one buffer
        final ByteBufWriter again = writer.begin(UnpooledByteBufAllocator.DEFAULT);
        again.lines().append("u".repeat(200));
        final ByteBuf fits = again.finish();
        Assertions.assertFalse(fits instanceof CompositeByteBuf);
        Assertions.assertEquals("u".repeat(200), utf8(fits));
    }

    private static List<Integer> capacitiesOf(final List<ByteBuf> buffers) {
        final List<Integer> capacities = new ArrayList<>();
        for (int i = 0; i < buffers.size(); i++) {
            if (!(buffers.get(i) instanceof CompositeByteBuf)) { // the composite holding them is not a part
                capacities.add(buffers.get(i).capacity());
            }
        }
        return capacities;
    }

    @Test
    void theThreadKeepsItsWriterAndItsArrayWhateverTheSizeOfTheText() {
        final ByteBufWriter first = ByteBufWriter.start(Charset.UTF8, UnpooledByteBufAllocator.DEFAULT);
        first.lines().append("w".repeat(3 * ByteBufWriter.MAX_ARRAY_BYTES));
        final ByteBuf text = first.finish();
        Assertions.assertEquals(3 * ByteBufWriter.MAX_ARRAY_BYTES, text.readableBytes());
        text.release();

        final ByteBufWriter next = ByteBufWriter.start(Charset.UTF8, UnpooledByteBufAllocator.DEFAULT);
        try {
            Assertions.assertSame(first, next);
            Assertions.assertEquals(ByteBufWriter.MAX_ARRAY_BYTES, next.output().array().length);
        } finally {
            next.abort();
        }
    }

    @Test
    void aTextStartedWithinAnotherHasAWriterOfItsOwn() {
        final ByteBufWriter outer = ByteBufWriter.start(Charset.UTF8, UnpooledByteBufAllocator.DEFAULT);
        outer.lines().append("outer ");
        final ByteBufWriter inner = ByteBufWriter.start(Charset.UTF8, UnpooledByteBufAllocator.DEFAULT);
        Assertions.assertNotSame(outer, inner);
        inner.lines().append("inner");
        final String innerText = utf8(inner.finish());
        outer.lines().append(innerText);
        Assertions.assertEquals("outer inner", utf8(outer.finish()));

        final ByteBufWriter again = ByteBufWriter.start(Charset.UTF8, UnpooledByteBufAllocator.DEFAULT);
        again.abort();
        Assertions.assertSame(outer, again, "The thread's writer was not given back");
    }

    private static ByteBufWriter small() {
        return new ByteBufWriter(Charset.UTF8, SMALL, SMALL);
    }

    private static byte[] viaArray(final Charset charset, final Consumer<JsonGenerator> document) {
        final ByteArrayJsonGenerator generator = new ByteArrayJsonGenerator(charset == Charset.US_ASCII
                ? new AsciiByteArrayWriter(16)
                : new Utf8ByteArrayWriter(16));
        document.accept(generator.start());
        final ByteArray bytes = generator.finish();
        return Arrays.copyOfRange(bytes.array(), bytes.start(), bytes.start() + bytes.length());
    }

    private static byte[] viaByteBuf(final Charset charset, final Consumer<JsonGenerator> document) {
        final ByteBufWriter writer = new ByteBufWriter(charset, SMALL, SMALL).begin(UnpooledByteBufAllocator.DEFAULT);
        document.accept(writer.json());
        final ByteBuf buffer = writer.finish();
        final byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        buffer.release();
        return bytes;
    }

    private static String utf8(final ByteBuf text) {
        try {
            return text.toString(StandardCharsets.UTF_8);
        } finally {
            text.release();
        }
    }

    /**
     * @param random the source of the chars
     * @param length at least this many chars
     * @return ASCII, Cyrillic, symbols, escapes and surrogate pairs, the pairs landing anywhere against the
     *         buffers
     */
    private static String text(final Random random, final int length) {
        final StringBuilder text = new StringBuilder();
        while (text.length() < length) {
            switch (random.nextInt(5)) {
                case 0:
                    text.append((char) ('a' + random.nextInt(26)));
                    break;
                case 1:
                    text.append((char) ('\u0430' + random.nextInt(32)));
                    break;
                case 2:
                    text.append("\u20ac\"\\\n");
                    break;
                case 3:
                    text.appendCodePoint(0x1F600 + random.nextInt(50)); // a surrogate pair
                    break;
                default:
                    text.append(' ');
                    break;
            }
        }
        return text.toString();
    }

    /**
     * @param taken where every buffer handed out is recorded
     * @param failAt the number of the buffer, from 1 and composites counted, which fails to be allocated, as
     *               direct memory runs out; -1 for none
     * @return the unpooled allocator, recording every buffer it hands out
     */
    private static ByteBufAllocator tracking(final List<ByteBuf> taken, final int failAt) {
        final ByteBufAllocator delegate = UnpooledByteBufAllocator.DEFAULT;
        return (ByteBufAllocator) Proxy.newProxyInstance(ByteBufAllocator.class.getClassLoader(),
                new Class<?>[] {ByteBufAllocator.class}, (proxy, method, args) -> {
                    if (ByteBuf.class.isAssignableFrom(method.getReturnType()) && taken.size() + 1 == failAt) {
                        throw new OutOfMemoryError("Cannot reserve direct memory");
                    }
                    final Object result = method.invoke(delegate, args);
                    if (result instanceof ByteBuf) {
                        taken.add((ByteBuf) result);
                    }
                    return result;
                });
    }
}
