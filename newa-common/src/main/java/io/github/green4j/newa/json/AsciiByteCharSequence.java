package io.github.green4j.newa.json;

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
        return new String(bytes, start, end);
    }

    @Override
    public String toString() {
        if (bytes == null) {
            return "null";
        }
        return new String(bytes, 0, length);
    }
}
