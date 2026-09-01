package io.github.green4j.newa.rest.files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ByteRangeTest {
    private static final long SIZE = 1000;

    @Test
    public void testNoHeaderMeansTheWholeFile() {
        Assertions.assertNull(ByteRange.parse(null, SIZE));
    }

    @Test
    public void testFirstAndLast() {
        final ByteRange range = ByteRange.parse("bytes=100-199", SIZE);
        Assertions.assertNotNull(range);
        Assertions.assertEquals(100, range.offset());
        Assertions.assertEquals(100, range.length());
    }

    @Test
    public void testFirstAlone() {
        final ByteRange range = ByteRange.parse("bytes=900-", SIZE);
        Assertions.assertNotNull(range);
        Assertions.assertEquals(900, range.offset());
        Assertions.assertEquals(100, range.length());
    }

    @Test
    public void testSuffix() {
        final ByteRange range = ByteRange.parse("bytes=-100", SIZE);
        Assertions.assertNotNull(range);
        Assertions.assertEquals(900, range.offset());
        Assertions.assertEquals(100, range.length());
    }

    @Test
    public void testSuffixLongerThanTheFileIsTheWholeFile() {
        final ByteRange range = ByteRange.parse("bytes=-5000", SIZE);
        Assertions.assertNotNull(range);
        Assertions.assertEquals(0, range.offset());
        Assertions.assertEquals(SIZE, range.length());
    }

    @Test
    public void testLastPastTheEndIsClamped() {
        final ByteRange range = ByteRange.parse("bytes=990-5000", SIZE);
        Assertions.assertNotNull(range);
        Assertions.assertEquals(990, range.offset());
        Assertions.assertEquals(10, range.length());
    }

    @Test
    public void testWholeFile() {
        final ByteRange range = ByteRange.parse("bytes=0-", SIZE);
        Assertions.assertNotNull(range);
        Assertions.assertEquals(0, range.offset());
        Assertions.assertEquals(SIZE, range.length());
    }

    @Test
    public void testCaseOfTheUnitDoesNotMatter() {
        Assertions.assertNotNull(ByteRange.parse("BYTES=0-1", SIZE));
    }

    @Test
    public void testFirstPastTheEndCannotBeSatisfied() {
        Assertions.assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=1000-", SIZE));
        Assertions.assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=5000-6000", SIZE));
    }

    @Test
    public void testZeroSuffixCannotBeSatisfied() {
        Assertions.assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=-0", SIZE));
    }

    @Test
    public void testNoByteOfAnEmptyFileCanBeAskedFor() {
        Assertions.assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=0-", 0));
        Assertions.assertSame(ByteRange.UNSATISFIABLE, ByteRange.parse("bytes=-10", 0));
    }

    @Test
    public void testWhatIsNotUnderstoodIsIgnoredRatherThanRefused() {
        Assertions.assertNull(ByteRange.parse("bytes=0-99,200-299", SIZE)); // several ranges
        Assertions.assertNull(ByteRange.parse("items=0-99", SIZE));         // another unit
        Assertions.assertNull(ByteRange.parse("bytes=199-100", SIZE));      // backwards
        Assertions.assertNull(ByteRange.parse("bytes=-", SIZE));
        Assertions.assertNull(ByteRange.parse("bytes=", SIZE));
        Assertions.assertNull(ByteRange.parse("bytes=abc-def", SIZE));
        Assertions.assertNull(ByteRange.parse("bytes=1-2-3", SIZE));
        Assertions.assertNull(ByteRange.parse("nonsense", SIZE));
    }
}
