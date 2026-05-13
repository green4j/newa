package io.github.green4j.newa.json;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class AsciiByteCharSequenceTest {

    @Test
    public void testLengthReflectsSetLength() {
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(10);
        Assertions.assertEquals(0, cs.length());
        cs.setLength(5);
        Assertions.assertEquals(5, cs.length());
    }

    @Test
    public void testCharAt() {
        final byte[] data = "Hello".getBytes(
                StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        cs.setLength(data.length);

        Assertions.assertEquals('H', cs.charAt(0));
        Assertions.assertEquals('e', cs.charAt(1));
        Assertions.assertEquals('o', cs.charAt(4));
    }

    @Test
    public void testToString() {
        final byte[] data = "World".getBytes(
                StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        cs.setLength(data.length);

        Assertions.assertEquals("World", cs.toString());
    }

    @Test
    public void testToStringPartialLength() {
        final byte[] data = "Hello World".getBytes(
                StandardCharsets.US_ASCII);
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        cs.setLength(5);

        Assertions.assertEquals("Hello", cs.toString());
    }

    @Test
    public void testBytesReturnsUnderlyingArray() {
        final byte[] data = {65, 66, 67};
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(data);
        Assertions.assertSame(data, cs.bytes());
    }

    @Test
    public void testConstructorWithSize() {
        final AsciiByteCharSequence cs =
                new AsciiByteCharSequence(32);
        Assertions.assertEquals(32, cs.bytes().length);
        Assertions.assertEquals(0, cs.length());
    }
}
