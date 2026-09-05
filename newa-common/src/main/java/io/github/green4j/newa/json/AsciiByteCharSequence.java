/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

/**
 * A {@link CharSequence} view over a byte array of ASCII, so text can be read out of a buffer without
 * decoding it into a {@link String} first. The array is the caller's and is never copied: what this reads
 * changes when the bytes do, and {@link #setLength(int)} is what makes the last write visible.
 * <p>
 * Nothing here decodes: a byte is the character its value casts to, which is the same rule in
 * {@link #charAt(int)}, {@link #subSequence(int, int)} and {@link #toString()}. A byte outside ASCII is
 * therefore not text this reads.
 */
public class AsciiByteCharSequence implements CharSequence {
    private final byte[] bytes;
    private int length;

    public AsciiByteCharSequence(final int size) {
        this(new byte[size]);
    }

    public AsciiByteCharSequence(final byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] bytes() {
        return bytes;
    }

    public void setLength(final int length) {
        this.length = length;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public char charAt(final int index) {
        return (char) bytes[index];
    }

    @Override
    public CharSequence subSequence(final int start, final int end) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException(
                    "start " + start + ", end " + end + ", length " + length);
        }
        // built with charAt's own rule rather than decoded with a charset: the platform's would make the
        // same bytes read differently on two machines, and this array is ASCII
        final char[] chars = new char[end - start];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = charAt(start + i);
        }
        return new String(chars);
    }

    @Override
    public String toString() {
        if (bytes == null) {
            return "null";
        }
        return subSequence(0, length).toString();
    }
}
