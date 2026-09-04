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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Date;

class EntityTagTest {
    private static final long MODIFIED = 1_700_000_000_000L;
    private static final long SIZE = 4096;

    private static final String TAG = EntityTag.of(MODIFIED, SIZE);

    @Test
    public void testATagIsQuotedAndStrong() {
        Assertions.assertTrue(TAG.startsWith("\""), TAG);
        Assertions.assertTrue(TAG.endsWith("\""), TAG);
        // a W/ would be the more cautious claim and would cost every resumed download its range
        Assertions.assertFalse(TAG.startsWith("W/"), TAG);
    }

    @Test
    public void testTheSameFileGivesTheSameTag() {
        Assertions.assertEquals(TAG, EntityTag.of(MODIFIED, SIZE));
    }

    @Test
    public void testAFileWhichChangedGivesAnother() {
        Assertions.assertNotEquals(TAG, EntityTag.of(MODIFIED + 1, SIZE));
        Assertions.assertNotEquals(TAG, EntityTag.of(MODIFIED, SIZE + 1));
    }

    @Test
    public void testTheTagItselfMatches() {
        Assertions.assertTrue(EntityTag.matches(TAG, TAG));
    }

    @Test
    public void testAnotherTagDoesNot() {
        Assertions.assertFalse(EntityTag.matches(EntityTag.of(MODIFIED, SIZE + 1), TAG));
    }

    @Test
    public void testNoHeaderIsNoMatch() {
        Assertions.assertFalse(EntityTag.matches(null, TAG));
    }

    @Test
    public void testOneOfAListMatches() {
        Assertions.assertTrue(EntityTag.matches("\"other\", " + TAG + " ,\"third\"", TAG));
        Assertions.assertFalse(EntityTag.matches("\"other\",\"third\"", TAG));
    }

    @Test
    public void testAStarIsAnyRepresentationAtAll() {
        Assertions.assertTrue(EntityTag.matches("*", TAG));
    }

    @Test
    public void testAWeakTagNamesTheSameFileHere() {
        // "have I got this already" is the weak question, and RFC 9110 asks it weakly
        Assertions.assertTrue(EntityTag.matches("W/" + TAG, TAG));
    }

    @Test
    public void testWhatCannotBeReadIsNoMatch() {
        Assertions.assertFalse(EntityTag.matches("nonsense", TAG));
        Assertions.assertFalse(EntityTag.matches("\"unterminated", TAG));
        Assertions.assertFalse(EntityTag.matches("", TAG));
    }

    @Test
    public void testNoIfRangeLeavesTheRangeAlone() {
        Assertions.assertTrue(EntityTag.rangeApplies(null, TAG, MODIFIED));
    }

    @Test
    public void testARangeOfTheFileThePeerHolds() {
        Assertions.assertTrue(EntityTag.rangeApplies(TAG, TAG, MODIFIED));
    }

    @Test
    public void testARangeOfAFileItNoLongerHolds() {
        Assertions.assertFalse(EntityTag.rangeApplies(EntityTag.of(MODIFIED, SIZE + 1), TAG, MODIFIED));
    }

    @Test
    public void testAWeakTagCannotBeRangedAgainst() {
        // it says the two are equivalent, not that they are the same bytes, and a range is about bytes
        Assertions.assertFalse(EntityTag.rangeApplies("W/" + TAG, TAG, MODIFIED));
    }

    @Test
    public void testADateWhichIsTheOneTheFileWasSentWith() {
        Assertions.assertTrue(
                EntityTag.rangeApplies(DateFormatter.format(new Date(MODIFIED)), TAG, MODIFIED));
    }

    @Test
    public void testAndOneWhichIsNot() {
        Assertions.assertFalse(
                EntityTag.rangeApplies(DateFormatter.format(new Date(MODIFIED - 60_000)), TAG, MODIFIED));
    }

    @Test
    public void testWhatCannotBeReadSendsTheWholeFile() {
        Assertions.assertFalse(EntityTag.rangeApplies("nonsense", TAG, MODIFIED));
        Assertions.assertFalse(EntityTag.rangeApplies("", TAG, MODIFIED));
    }
}
