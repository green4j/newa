/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

/**
 * A {@link MapBase} keyed by objects, compared with {@link Objects#equals} unless a subclass says otherwise.
 * It owns the keys; what a value is belongs to the subclass.
 * <p>
 * A walk - {@link #keyIterator()}, {@link #keys()} - goes in slot order, which is neither insertion order
 * nor any other order a caller may rely on, and a map written to during one is not walked again from where
 * it was.
 *
 * @param <K> the type of the keys.
 */
public abstract class ObjectMapBase<K> extends MapBase {

    protected Object[] keys;

    protected ObjectMapBase() {
        super();
    }

    protected ObjectMapBase(final int capacity) {
        super(capacity);
    }

    protected void putKey(final int idx, final K key) {
        keys[idx] = key;
    }

    protected boolean keyEquals(final K a, final K b) {
        return (Objects.equals(a, b));
    }

    @Override
    public void clear() {
        super.clear();
        Arrays.fill(keys, null);
    }

    @Override
    protected void free(final int idx) {
        super.free(idx);
        keys[idx] = null;
    }

    protected final class KeyIterator implements Iterator<K> {
        private int pos = -1;

        public KeyIterator() {
            move();
        }

        private void move() {
            do {
                pos++;
            } while (pos < keys.length && isEmpty(pos));
        }

        @Override
        public boolean hasNext() {
            return pos < keys.length;
        }

        @Override
        @SuppressWarnings("unchecked")
        public K next() {
            final K result = (K) keys[pos]; // unchecked
            move();
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    public Iterator<K> keyIterator() {
        return new KeyIterator();
    }

    @Override
    protected void allocTable(final int capacity) {
        super.allocTable(capacity);

        keys = new Object[capacity];
    }

    protected final int hashIndex(final K key) {
        return modHashCode(key, hashIndexes.length);
    }

    public boolean remove(final K key) {
        final int idx = find(key);

        if (idx == NULL) {
            return false;
        }

        free(idx);
        return true;
    }

    protected int find(final K key) {
        return find(hashIndex(key), key);
    }

    @SuppressWarnings("unchecked")
    protected int find(final int hashIdx, final K key) {
        for (int i = hashIndexes[hashIdx]; i != NULL; i = next[i]) {
            if (keyEquals(key, (K) keys[i])) { // unchecked
                return i;
            }
        }
        return NULL;
    }

    public boolean containsKey(final K key) {
        return find(key) != NULL;
    }

    public final boolean isEmpty() {
        return count == 0;
    }

    protected final class KeyEnumeration implements ResettableEnumeration<K> {
        private int pos = -1;

        private KeyEnumeration() {
            move();
        }

        private void move() {
            do {
                pos++;
            } while (pos < keys.length && isEmpty(pos));
        }

        @Override
        public boolean hasMoreElements() {
            return pos < keys.length;
        }

        @Override
        public void reset() {
            pos = -1;
            move();
        }

        @Override
        @SuppressWarnings("unchecked")
        public K nextElement() {
            final K result = (K) keys[pos]; // unchecked
            move();
            return result;
        }
    }

    public ResettableEnumeration<K> keys() {
        return new KeyEnumeration();
    }

    private static int modHashCode(final Object key, final int mod) {
        return computeModHashCode(Objects.hashCode(key), mod);
    }

    private static int computeModHashCode(final int key, final int mod) {
        int k = key;
        if (k == Integer.MIN_VALUE) {
            return 1;
        }
        if (k < 0) {
            k = -k;
        }
        return k % mod;
    }
}
