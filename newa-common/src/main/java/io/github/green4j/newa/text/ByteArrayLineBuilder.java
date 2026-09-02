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

package io.github.green4j.newa.text;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;

public class ByteArrayLineBuilder implements LineAppendable {
    public static final String NL = System.lineSeparator();
    public static final String DEFAULT_TAB = "    ";
    private static final String[] TABS_BY_LEVEL = new String[10];

    static {
        for (int i = 0; i < TABS_BY_LEVEL.length; i++) {
            TABS_BY_LEVEL[i] = DEFAULT_TAB.repeat(i);
        }
    }

    protected final ClearableByteArrayBufferingWriter writer;

    public ByteArrayLineBuilder(final ClearableByteArrayBufferingWriter writer) {
        this.writer = writer;
    }

    @Override
    public ByteArrayLineBuilder append(final CharSequence csq) {
        writer.append(csq);
        return this;
    }

    @Override
    public ByteArrayLineBuilder append(final char c) {
        writer.append(c);
        return this;
    }

    public ByteArrayLineBuilder appendln(final CharSequence csq) {
        append(csq);
        return appendln();
    }

    @Override
    public ByteArrayLineBuilder appendln(final char c) {
        append(c);
        return appendln();
    }

    @Override
    public ByteArrayLineBuilder appendln() {
        append(NL);
        return this;
    }

    @Override
    public ByteArrayLineBuilder append(final CharSequence csq, final int start, final int end) {
        writer.append(csq, start, end);
        return this;
    }

    @Override
    public LineAppendable tab(final int level) {
        if (level < TABS_BY_LEVEL.length) {
            writer.append(TABS_BY_LEVEL[level]);
            return this;
        }
        return tab(level, DEFAULT_TAB.length());
    }

    @Override
    public LineAppendable tab(final int level,
                              final int size) {
        if (size == DEFAULT_TAB.length()) {
            int levelsLeft = level;
            while (levelsLeft > 0) {
                final int tabIdx = Math.min(levelsLeft, TABS_BY_LEVEL.length - 1);
                writer.append(TABS_BY_LEVEL[tabIdx]);
                levelsLeft -= tabIdx;
            }
            return this;
        }
        for (int i = 0; i < level; i++) {
            for (int j = 0; j < size; j++) {
                append(" ");
            }
        }
        return this;
    }

    public ByteArray array() {
        return writer;
    }

    public void clear() {
        writer.clear();
    }

    /**
     * Size of the underlying buffer. The buffer grows to fit the largest text ever built and {@link #clear()}
     * never shrinks it back, so a caller which pools line builders can use this to decide whether a builder
     * is worth keeping.
     *
     * @return number of bytes the underlying buffer occupies
     */
    public int capacity() {
        final byte[] array = writer.array();
        return array == null ? 0 : array.length;
    }

    @Override
    public String toString() {
        return writer.toString();
    }
}
