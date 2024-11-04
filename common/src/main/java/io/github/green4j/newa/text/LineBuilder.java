package io.github.green4j.newa.text;

public class LineBuilder implements LineAppendable {
    protected final StringBuilder content = new StringBuilder();

    @Override
    public LineBuilder append(final CharSequence csq) {
        content.append(csq);
        return this;
    }

    @Override
    public LineBuilder append(final char c) {
        content.append(c);
        return this;
    }

    public LineBuilder appendln(final CharSequence csq) {
        append(csq);
        return appendln();
    }

    @Override
    public LineBuilder appendln(final char c) {
        append(c);
        return appendln();
    }

    @Override
    public LineBuilder appendln() {
        append('\n');
        return this;
    }

    @Override
    public LineBuilder append(final CharSequence csq, final int start, final int end) {
        content.append(csq, start, end);
        return this;
    }

    @Override
    public String toString() {
        return content.toString();
    }
}
