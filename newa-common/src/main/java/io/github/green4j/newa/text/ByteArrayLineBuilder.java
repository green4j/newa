/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.text;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;

/**
 * Builds text into a byte array which is reused line after line: {@link #clear()} empties it, {@link
 * #array()} hands out the bytes written so far. The buffer grows to the largest text ever built and is
 * never shrunk back.
 * <p>
 * Lines end with {@link #NL}, which is this machine's separator - a text response built with it carries
 * whatever the server runs on, so a protocol which needs one or the other appends it itself.
 */
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
     * The buffer grows to the largest text ever built and {@link #clear()} never shrinks it back, so a
     * caller which pools line builders reads this to decide whether one is worth keeping.
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
