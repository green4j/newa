package io.github.green4j.newa.text;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.ClearableByteArrayBufferingWriter;

public class ByteArrayLineBuilder implements LineAppendable {
    public static final String NL = System.lineSeparator();
    public static final String DEFAULT_TAB = "    ";
    private static final String[] TABS_BY_LEVEL = new String[10];

    static {
        for (int i = 0; i < TABS_BY_LEVEL.length; i++) {
            TABS_BY_LEVEL[i] = DEFAULT_TAB.repeat(i);
        }
    }

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
        append(NL);
        return this;
    }

    @Override
    public ByteArrayLineBuilder append(final CharSequence csq, final int start, final int end) {
        writer.append(csq, start, end);
        return this;
    }

    @Override
    public LineAppendable tab(final int level) {
        if (level < TABS_BY_LEVEL.length) {
            writer.append(TABS_BY_LEVEL[level]);
            return this;
        }
        return tab(level, DEFAULT_TAB.length());
    }

    @Override
    public LineAppendable tab(final int level,
                              final int size) {
        if (size == DEFAULT_TAB.length()) {
            int levelsLeft = level;
            while (levelsLeft > 0) {
                final int tabIdx = Math.min(levelsLeft, TABS_BY_LEVEL.length - 1);
                writer.append(TABS_BY_LEVEL[tabIdx]);
                levelsLeft -= tabIdx;
            }
            return this;
        }
        for (int i = 0; i < level; i++) {
            for (int j = 0; j < size; j++) {
                append(" ");
            }
        }
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
