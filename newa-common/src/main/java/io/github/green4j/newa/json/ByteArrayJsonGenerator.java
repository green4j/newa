/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.json;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;
import io.github.green4j.jelly.JsonGenerator;

public class ByteArrayJsonGenerator {
    /**
     * Compact rather than green-jelly's indented default. Whitespace on the wire costs bandwidth on every
     * response and buys nothing a client wants: a document meant to be read by a person is piped through
     * something which formats it anyway.
     */
    private final JsonGenerator generator = new JsonGenerator(false);
    private final ClearableByteArrayBufferingWriter writer;

    public ByteArrayJsonGenerator(final ClearableByteArrayBufferingWriter writer) {
        this.writer = writer;
        generator.setOutput(writer);
    }

    public ByteArray finish() {
        generator.eoj();
        return writer;
    }

    public JsonGenerator start() {
        writer.clear();
        generator.reset();
        return generator;
    }

    /**
     * Size of the underlying buffer. The buffer grows to fit the largest document ever generated and
     * {@link #start()} never shrinks it back, so a caller which pools generators can use this to decide
     * whether a generator is worth keeping.
     *
     * @return number of bytes the underlying buffer occupies
     */
    public int capacity() {
        final byte[] array = writer.array();
        return array == null ? 0 : array.length;
    }

    /**
     * Length of the document generated so far, or of the last one generated if {@link #start()} has not been
     * called since.
     *
     * @return number of bytes written
     */
    public int length() {
        return writer.length();
    }
}
