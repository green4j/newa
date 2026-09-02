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

package io.github.green4j.newa.collections;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link ConcurrentHashMap} looked up by any {@link CharSequence} without building a {@link String} to do
 * it: the key of a lookup is a thread local flyweight over the sequence handed in, so a lookup allocates
 * nothing. A key which is stored is a {@code String} of its own.
 *
 * <p>Written to while it is being read, which is the point. Writing costs one entry rather than a copy of
 * the whole map - filling a map of a hundred thousand keys stays linear - and a reader never blocks a writer
 * nor waits for one.
 *
 * @param <T> the type of the values.
 */
public class CharSequenceToObjectMapConcurrent<T> extends ConcurrentHashMap<String, T> {
    private static final long serialVersionUID = 7940132434556711268L;

    private static final ThreadLocal<CharSequenceToObjectMap.KeyBuffer> KEY_BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(CharSequenceToObjectMap.KeyBuffer::new);

    public CharSequenceToObjectMapConcurrent(final int initialCapacity) {
        super(initialCapacity);
    }

    public CharSequenceToObjectMapConcurrent() {
        super();
    }

    @Override
    public final T get(final Object key) {
        return get((CharSequence) key);
    }

    public final T get(final CharSequence key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key);
        return super.get(buffer);
    }

    public final T get(final CharSequence key, final int start, final int end) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key, start, end);
        return super.get(buffer);
    }

    public T put(final CharSequence key, final T value) {
        return super.put(key.toString(), value);
    }

    public T putIfAbsent(final CharSequence key, final T value) {
        return super.putIfAbsent(key.toString(), value);
    }

    @Override
    public boolean containsKey(final Object key) {
        return containsKey((CharSequence) key);
    }

    public boolean containsKey(final CharSequence key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key);
        // not super.containsKey: ConcurrentHashMap answers that one by calling get(Object), which is
        // overridden here - it would hand the buffer back to itself and set it over itself
        return super.get(buffer) != null;
    }

    @Override
    public T remove(final Object key) {
        return remove((CharSequence) key);
    }

    public T remove(final CharSequence key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key);
        return super.remove(buffer);
    }

    public T remove(final CharSequence key, final int start, final int end) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key, start, end);
        return super.remove(buffer);
    }
}
