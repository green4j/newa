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

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;

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
