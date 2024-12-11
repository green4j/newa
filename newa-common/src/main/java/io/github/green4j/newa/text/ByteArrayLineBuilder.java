package io.github.green4j.newa.text;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;

public class ByteArrayLineBuilder implements LineAppendable {
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
        append('\n');
        return this;
    }

    @Override
    public ByteArrayLineBuilder append(final CharSequence csq, final int start, final int end) {
        writer.append(csq, start, end);
        return this;
    }

    public ByteArray array() {
        return writer;
    }

    public void clear() {
        writer.clear();
    }

    @Override
    public String toString() {
        return writer.toString();
    }
}
