/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.files;

import io.netty.handler.codec.DateFormatter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Date;
import java.util.stream.Stream;

class EntityTagTest {
    private static final long MODIFIED = 1_700_000_000_000L;
    private static final long SIZE = 4096;

    private static final String TAG = EntityTag.of(MODIFIED, SIZE);
    private static final String ANOTHER = EntityTag.of(MODIFIED, SIZE + 1);

    @Test
    public void testATagIsQuotedAndStrong() {
        Assertions.assertTrue(TAG.startsWith("\""), TAG);
        Assertions.assertTrue(TAG.endsWith("\""), TAG);
        // a W/ would be the more cautious claim and would cost every resumed download its range
        Assertions.assertFalse(TAG.startsWith("W/"), TAG);
    }

    @Test
    public void aTagNamesTheFileItWasMadeOf() {
        Assertions.assertEquals(TAG, EntityTag.of(MODIFIED, SIZE), "the same file gave another tag");
        Assertions.assertNotEquals(TAG, EntityTag.of(MODIFIED + 1, SIZE), "a file modified since");
        Assertions.assertNotEquals(TAG, ANOTHER, "a file of another length");
    }

    /**
     * The If-None-Match header, which asks "have I got this already".
     *
     * @return one case per header: what it says, and whether it names the file this server holds.
     */
    private static Stream<Arguments> ifNoneMatch() {
        return Stream.of(
                Arguments.of("the tag itself", TAG, true),
                Arguments.of("another tag", ANOTHER, false),
                Arguments.of("no header at all", null, false),
                Arguments.of("one of a list", "\"other\", " + TAG + " ,\"third\"", true),
                Arguments.of("a list without it", "\"other\",\"third\"", false),
                Arguments.of("a star, which is any representation at all", "*", true),
                // "have I got this already" is the weak question, and RFC 9110 asks it weakly
                Arguments.of("a weak tag, which names the same file here", "W/" + TAG, true),
                Arguments.of("what cannot be read", "nonsense", false),
                Arguments.of("an unterminated quote", "\"unterminated", false),
                Arguments.of("an empty header", "", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ifNoneMatch")
    public void matches(final String what,
                        final String header,
                        final boolean expected) {
        Assertions.assertEquals(expected, EntityTag.matches(header, TAG), what);
    }

    /**
     * The If-Range header, which asks whether a resumed download may go on from where it stopped.
     *
     * @return one case per header: what it says, and whether the range still applies.
     */
    private static Stream<Arguments> ifRange() {
        return Stream.of(
                Arguments.of("no header, which leaves the range alone", null, true),
                Arguments.of("the tag of the file the peer holds", TAG, true),
                Arguments.of("the tag of one it no longer holds", ANOTHER, false),
                // a weak tag says the two are equivalent, not that they are the same bytes, and a range
                // is about bytes
                Arguments.of("a weak tag, which cannot be ranged against", "W/" + TAG, false),
                Arguments.of("the date the file was sent with",
                        DateFormatter.format(new Date(MODIFIED)), true),
                Arguments.of("a date which is not that one",
                        DateFormatter.format(new Date(MODIFIED - 60_000)), false),
                // what cannot be read sends the whole file rather than a range of the wrong one
                Arguments.of("what cannot be read", "nonsense", false),
                Arguments.of("an empty header", "", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ifRange")
    public void rangeApplies(final String what,
                             final String header,
                             final boolean expected) {
        Assertions.assertEquals(expected, EntityTag.rangeApplies(header, TAG, MODIFIED), what);
    }
}
