/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.text;

/**
 * An {@link Appendable} which also knows lines and indentation, and which throws no {@link
 * java.io.IOException}: it writes into memory, so there is nothing for a caller to handle. Every method
 * returns this one, so a whole line is one chain.
 */
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
