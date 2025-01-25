package io.github.green4j.newa.text;

public interface LineAppendable extends Appendable {
    LineAppendable append(CharSequence csq);

    LineAppendable append(char c);

    LineAppendable append(CharSequence csq, int start, int end);

    LineAppendable appendln(CharSequence csq);

    LineAppendable appendln(char c);

    LineAppendable appendln();

    LineAppendable tab(int level);

    LineAppendable tab(int level, int size);
}
