/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

/**
 * One byte range of a file, parsed from a {@code Range} header.
 * <p>
 * Only a single range is understood. Everything else - several ranges, another unit, a reversed pair, plain
 * garbage - is ignored rather than refused, which RFC 7233 allows and which is what a client which sent
 * something odd copes with best: it gets the whole file.
 */
final class ByteRange {
    /**
     * The range named a first byte the file does not have, which is the one case a server must not answer
     * with the content anyway: it is a 416 and a {@code Content-Range: bytes} of the size.
     */
    static final ByteRange UNSATISFIABLE = new ByteRange(-1, -1);

    private static final String UNIT = "bytes=";

    /**
     * Reads the one range this understands.
     *
     * @param value of the {@code Range} header, or null when there was none
     * @param fileSize the range is taken of
     * @return the range to send, {@link #UNSATISFIABLE}, or null to send the whole file
     */
    static ByteRange parse(final CharSequence value,
                           final long fileSize) {
        if (value == null) {
            return null;
        }

        final int length = value.length();
        if (length <= UNIT.length()) {
            return null;
        }
        for (int i = 0; i < UNIT.length(); i++) {
            if (Character.toLowerCase(value.charAt(i)) != UNIT.charAt(i)) {
                return null;
            }
        }

        int dash = -1;
        for (int i = UNIT.length(); i < length; i++) {
            final char c = value.charAt(i);
            if (c == ',') {
                return null; // several ranges: answering them means multipart/byteranges, which this does not
            }
            if (c == '-') {
                if (dash > -1) {
                    return null;
                }
                dash = i;
            } else if (c < '0' || c > '9') {
                return null;
            }
        }
        if (dash < 0) {
            return null;
        }

        final long first = parseDigits(value, UNIT.length(), dash);
        final long last = parseDigits(value, dash + 1, length);

        if (first < 0 && last < 0) {
            return null; // a dash and no numbers around it says nothing at all
        }

        if (first < 0) { // "-<suffix>": the last so many bytes
            if (last <= 0) {
                return UNSATISFIABLE;
            }
            if (fileSize == 0) {
                return UNSATISFIABLE;
            }
            final long from = last >= fileSize ? 0 : fileSize - last;
            return new ByteRange(from, fileSize - from);
        }

        if (first >= fileSize) { // also covers an empty file, of which no byte can be asked for
            return UNSATISFIABLE;
        }

        if (last < 0) { // "<first>-": from there to the end
            return new ByteRange(first, fileSize - first);
        }

        if (last < first) {
            return null;
        }

        final long to = last >= fileSize ? fileSize - 1 : last;
        return new ByteRange(first, to - first + 1);
    }

    /**
     * @param value to read from
     * @param from index of the first digit
     * @param to index past the last digit
     * @return the number, or -1 when there were no digits or they do not fit a long
     */
    private static long parseDigits(final CharSequence value,
                                    final int from,
                                    final int to) {
        if (from >= to) {
            return -1;
        }
        long result = 0;
        for (int i = from; i < to; i++) {
            result = result * 10 + (value.charAt(i) - '0');
            if (result < 0) { // a range longer than a file can be is a range of the whole file
                return Long.MAX_VALUE;
            }
        }
        return result;
    }

    private final long offset;
    private final long length;

    private ByteRange(final long offset,
                      final long length) {
        this.offset = offset;
        this.length = length;
    }

    long offset() {
        return offset;
    }

    long length() {
        return length;
    }

    @Override
    public String toString() {
        return this == UNSATISFIABLE ? "unsatisfiable" : "bytes " + offset + '-' + (offset + length - 1);
    }
}
