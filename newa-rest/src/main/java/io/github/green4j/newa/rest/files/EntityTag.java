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


package io.github.green4j.newa.rest.files;

import io.netty.handler.codec.DateFormatter;

import java.util.Date;

/**
 * The validator a file is answered with, and the two conditional headers which are asked against it.
 * <p>
 * The tag is what the file system says about the file - when it changed and how large it is - and nothing
 * else: a server which sends from the page cache must not be a server which reads every file to hash it.
 * That pair changes whenever the content does, short of a change which keeps the size and lands within the
 * same millisecond as the one before it.
 * <p>
 * It is a <b>strong</b> tag all the same, quoted and without the {@code W/}. A weak one would be the more
 * cautious claim, and it would cost the thing this was added for: {@code If-Range} may only be answered
 * against a strong validator, so a weak tag means every resumed download silently starts again from the
 * first byte.
 */
final class EntityTag {
    private static final String WEAK = "W/";

    /**
     * @param lastModifiedMillis of the file, as the file system reports it
     * @param size of what is being answered with
     * @return the {@code ETag} to send, quotes included
     */
    static String of(final long lastModifiedMillis,
                     final long size) {
        return '"' + Long.toHexString(lastModifiedMillis) + '-' + Long.toHexString(size) + '"';
    }

    /**
     * Whether an {@code If-None-Match} names the file which is about to be sent, which is what makes the
     * answer a {@code 304} instead.
     * <p>
     * The comparison is the weak one RFC 9110 asks for here: {@code W/"x"} and {@code "x"} name the same
     * file, because what this question means is "have I got this already", not "may I patch it".
     *
     * @param value of the header, or null when there was none
     * @param etag of the file
     * @return whether the peer already has it
     */
    static boolean matches(final CharSequence value,
                           final CharSequence etag) {
        if (value == null) {
            return false;
        }
        final int length = value.length();
        int i = 0;
        while (i < length) {
            final char c = value.charAt(i);
            if (c == ',' || c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (c == '*') {
                return true; // any representation at all, and there is one: that is the whole question
            }
            if (startsWeak(value, i)) {
                i += WEAK.length();
            }
            if (i >= length || value.charAt(i) != '"') {
                return false; // not a list of tags, and guessing what it meant is not this method's business
            }
            final int from = i++;
            while (i < length && value.charAt(i) != '"') {
                i++;
            }
            if (i >= length) {
                return false; // unterminated: the same
            }
            i++; // past the closing quote, which is part of the tag
            if (equal(value, from, i, etag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a {@code Range} may be answered with a part of the file, or has to be ignored because what the
     * peer already holds is not what this file is now. Without the header there is nothing to disagree with.
     * <p>
     * A tag is compared strongly - {@code W/} never matches - and a date has to be the one this file is
     * being sent with, to the second the header can carry. Anything else, including a value which cannot be
     * read at all, sends the whole file: a wrong range is spliced into a file the peer keeps, and a whole
     * file which was not needed is only a whole file.
     *
     * @param value of the header, or null when there was none
     * @param etag of the file
     * @param lastModifiedMillis of the file
     * @return whether the range still applies
     */
    static boolean rangeApplies(final CharSequence value,
                                final CharSequence etag,
                                final long lastModifiedMillis) {
        if (value == null) {
            return true;
        }
        final int length = value.length();
        int i = 0;
        while (i < length && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
            i++;
        }
        if (i >= length) {
            return false;
        }
        if (startsWeak(value, i)) {
            return false; // a weak validator says nothing about which bytes these are
        }
        if (value.charAt(i) == '"') {
            return equal(value, i, length, etag);
        }
        final Date date = DateFormatter.parseHttpDate(value, i, length);
        if (date == null) {
            return false;
        }
        // the header carries seconds; a file which changed within the second it was sent in cannot be told
        // apart here, and it is the range which gives way
        return lastModifiedMillis / 1000 == date.getTime() / 1000;
    }

    private static boolean startsWeak(final CharSequence value,
                                      final int from) {
        return from + WEAK.length() <= value.length()
                && value.charAt(from) == WEAK.charAt(0)
                && value.charAt(from + 1) == WEAK.charAt(1);
    }

    /**
     * @param value one tag is taken from
     * @param from index of its first character
     * @param to index past its last
     * @param etag to compare it with
     * @return whether the two are the same tag
     */
    private static boolean equal(final CharSequence value,
                                 final int from,
                                 final int to,
                                 final CharSequence etag) {
        if (to - from != etag.length()) {
            return false;
        }
        for (int i = 0; i < etag.length(); i++) {
            if (value.charAt(from + i) != etag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private EntityTag() {
    }
}
