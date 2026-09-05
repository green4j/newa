/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

/**
 * Turns the tail of a request path into a relative path a root can be asked for, and refuses everything which
 * could ask for something else. Nothing here touches the file system: what it refuses is refused before a
 * single stat is spent on it.
 */
final class FilePaths {
    private FilePaths() {
    }

    /**
     * Percent-decodes {@code path[from, to)} into {@code into}, dropping the slashes which surround it.
     * <p>
     * The characters come from the request line, which the decoder read a byte to a character, so a character
     * is written back as the byte it was and a multi-byte name survives to be read as UTF-8 by the caller.
     *
     * @param path the request came in on
     * @param from index of the first character of the tail
     * @param to index past its last character
     * @param into buffer to decode into
     * @return the number of bytes written, or -1 when the tail is malformed, holds an encoded separator, or
     *         does not fit
     */
    static int decode(final CharSequence path,
                      final int from,
                      final int to,
                      final byte[] into) {
        int start = from;
        while (start < to && path.charAt(start) == '/') {
            start++;
        }
        int end = to;
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }

        int length = 0;
        for (int i = start; i < end; i++) {
            final char c = path.charAt(i);
            if (c > 0xFF) {
                return -1; // never came off the wire: the request line is read a byte to a character
            }
            if (length >= into.length) {
                return -1;
            }
            if (c != '%') {
                into[length++] = (byte) c;
                continue;
            }
            if (i + 2 >= end) {
                return -1;
            }
            final int high = hex(path.charAt(i + 1));
            final int low = hex(path.charAt(i + 2));
            if (high < 0 || low < 0) {
                return -1;
            }
            final byte value = (byte) ((high << 4) | low);
            if (value == '/' || value == '\\') {
                // an encoded separator is a separator the router never saw, so a path holding one would be
                // matched as one thing and resolved as another
                return -1;
            }
            into[length++] = value;
            i += 2;
        }
        return length;
    }

    private static int hex(final char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * The decoded path is what the root is resolved against, so this is where a request stops being able to
     * name anything but a file under it: no {@code ..}, no empty segment, no separator the platform might
     * take for one, and no NUL - which some file systems end a name at, and which would make a refused
     * extension a served one.
     *
     * @param relative decoded path of the file within its root
     * @return whether it may be resolved
     */
    static boolean isSafe(final String relative) {
        if (relative.isEmpty()) {
            return false;
        }
        int segmentStart = 0;
        for (int i = 0; i <= relative.length(); i++) {
            final char c = i < relative.length() ? relative.charAt(i) : '/';
            if (c == 0 || c == '\\') {
                return false;
            }
            if (c != '/') {
                continue;
            }
            final int length = i - segmentStart;
            if (length == 0) {
                return false; // an empty segment: "a//b", or a name starting at the root
            }
            if (length <= 2 && relative.charAt(segmentStart) == '.') {
                if (length == 1 || relative.charAt(segmentStart + 1) == '.') {
                    return false; // "." or ".."
                }
            }
            segmentStart = i + 1;
        }
        return true;
    }
}
