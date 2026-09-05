/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

import java.util.HashMap;

/**
 * A {@link HashMap} looked up by any {@link CharSequence} without building a {@link String} to do it: the
 * key of a lookup is a flyweight over the sequence handed in, so a lookup allocates nothing. A key which is
 * stored is a {@code String} of its own.
 *
 * <p>That flyweight belongs to the map, so <b>one map per thread</b>: two threads looking up at once would
 * be looking up each other's keys. {@link CharSequenceToObjectMapConcurrent} is the one to share.
 *
 * @param <T> the type of the values.
 */
public class CharSequenceToObjectMap<T> extends HashMap<String, T> {
    private static final long serialVersionUID = -5687516993124229947L;

    private final transient KeyBuffer buffer = new KeyBuffer();

    public CharSequenceToObjectMap() {
        super();
    }

    @Override
    public T get(final Object key) {
        return get((CharSequence) key);
    }

    public T get(final CharSequence key) {
        buffer.set(key);
        return super.get(buffer);
    }

    public T get(final CharSequence key, final int start, final int end) {
        buffer.set(key, start, end);
        return super.get(buffer);
    }

    public T put(final CharSequence key, final T value) {
        return super.put(key.toString(), value);
    }

    public T put(final CharSequence key, final int start, final int end, final T value) {
        return super.put(key.subSequence(start, end).toString(), value);
    }

    @Override
    public boolean containsKey(final Object key) {
        buffer.set((CharSequence) key);
        return super.containsKey(buffer);
    }

    public boolean containsKey(final CharSequence key, final int start, final int end) {
        buffer.set(key, start, end);
        return super.containsKey(buffer);
    }

    public boolean containsKey(final CharSequence key) {
        buffer.set(key);
        return super.containsKey(buffer);
    }

    @Override
    public T remove(final Object key) {
        buffer.set((CharSequence) key);
        return super.remove(buffer);
    }

    public T remove(final CharSequence key, final int start, final int end) {
        buffer.set(key, start, end);
        return super.remove(buffer);
    }

    public T remove(final CharSequence key) {
        buffer.set(key);
        return super.remove(buffer);
    }

    static class KeyBuffer implements CharSequence {
        private CharSequence delegate;
        private int start;
        private int end;

        KeyBuffer() {
            delegate = null;
            start = -1;
            end = -1;
        }

        void set(final CharSequence delegate) {
            this.delegate = delegate;
            start = 0;
            end = delegate.length();
        }

        void set(final CharSequence delegate, final int start, final int end) {
            this.delegate = delegate;
            this.start = start;
            this.end = end;
        }

        public char charAt(final int index) {
            return (delegate.charAt(start + index));
        }

        public CharSequence subSequence(final int start, final int end) {
            return delegate.subSequence(this.start + start, this.start + end).toString();
        }

        public int length() {
            return end - start;
        }

        @Override
        public String toString() {
            return delegate.subSequence(start, end).toString();
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean equals(final Object other) {
            return this == other
                    || other instanceof CharSequence
                    && CharSequence.compare(this, (CharSequence) other) == 0;
        }

        @Override
        public int hashCode() {
            int result = 0;
            for (int i = 0; i < length(); i++) {
                result = 31 * result + charAt(i);
            }
            return result;
        }
    }
}