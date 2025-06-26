package io.github.green4j.newa.collections;

import java.util.HashMap;

public class CharSequenceToObjectMapReadSafe<T> extends HashMap<String, T> {
    private static final long serialVersionUID = 562498820763181265L;

    private static final ThreadLocal<CharSequenceToObjectMap.KeyBuffer> KEY_BUFFER_THREAD_LOCAL =
            ThreadLocal.withInitial(CharSequenceToObjectMap.KeyBuffer::new);

    public CharSequenceToObjectMapReadSafe(final int initialCapacity,
                                   final float loadFactor) {
        super(initialCapacity, loadFactor);
    }

    public CharSequenceToObjectMapReadSafe(final int initialCapacity) {
        super(initialCapacity);
    }

    public CharSequenceToObjectMapReadSafe() {
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

    public T put(final CharSequence key, final int start, final int end, final T value) {
        return super.put(key.subSequence(start, end).toString(), value);
    }

    @Override
    public boolean containsKey(final Object key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set((CharSequence) key);
        return super.containsKey(buffer);
    }

    public boolean containsKey(final CharSequence key, final int start, final int end) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key, start, end);
        return super.containsKey(buffer);
    }

    public boolean containsKey(final CharSequence key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key);
        return super.containsKey(buffer);
    }

    @Override
    public T remove(final Object key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set((CharSequence) key);
        return super.remove(buffer);
    }

    public T remove(final CharSequence key, final int start, final int end) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key, start, end);
        return super.remove(buffer);
    }

    public T remove(final CharSequence key) {
        final CharSequenceToObjectMap.KeyBuffer buffer = KEY_BUFFER_THREAD_LOCAL.get();
        buffer.set(key);
        return super.remove(buffer);
    }
}