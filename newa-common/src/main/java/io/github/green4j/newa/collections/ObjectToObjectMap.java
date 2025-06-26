package io.github.green4j.newa.collections;

import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Consumer;

public class ObjectToObjectMap<K, V> extends ObjectMapBase<K> implements Iterable<V> {
    protected Object[] values;

    public ObjectToObjectMap() {
        super();
    }

    public ObjectToObjectMap(final int capacity) {
        super(capacity);
    }

    @Override
    protected void allocTable(final int capacity) {
        super.allocTable(capacity);
        values = new Object[capacity];
    }

    @Override
    public void clear() {
        super.clear();
        Arrays.fill(values, null);
    }

    @Override
    protected void free(final int idx) {
        super.free(idx);
        values[idx] = null;
    }

    class ElementIterator implements Iterator<V> {
        private int currentIdx = -1;

        ElementIterator() {
            move();
        }

        private void move() {
            do {
                currentIdx++;
            } while (currentIdx < values.length && isEmpty(currentIdx));
        }

        @Override
        public boolean hasNext() {
            return (currentIdx < values.length);
        }

        @Override
        @SuppressWarnings("unchecked")
        public V next() {
            final V value = (V) values[currentIdx]; // unchecked
            move();
            return value;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public Iterator<V> iterator() {
        return new ElementIterator();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void forEach(final Consumer<? super V> consumer) {
        for (int i = 0; i < values.length; i++) {
            if (isFilled(i)) {
                consumer.accept((V) values[i]); // unchecked
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void resizeTable(final int newSize) {
        final int curLength = values.length;
        final Object[] saveKeys = keys;
        final Object[] saveValues = values;
        final int[] savePrev = prev;

        allocTable(newSize);

        for (int i = 0; i < curLength; i++) {
            if (savePrev[i] != NULL) {
                putNewNoSpaceCheck((K) saveKeys[i], (V) saveValues[i]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public V get(final K key,
                 final V notFoundValue) {
        final int pos = find(key);
        return pos == NULL ? notFoundValue : (V) values[pos]; // unchecked
    }

    @SuppressWarnings("unchecked")
    public V remove(final K key, final V notFoundValue) {
        final int idx = find(key);
        if (idx == NULL) {
            return notFoundValue;
        }

        final V value = (V) values[idx]; // unchecked

        free(idx);

        return value;
    }

    private void putNewNoSpaceCheck(final K key, final V value) {
        final int hashIndex = hashIndex(key);

        int idx = find(hashIndex, key);
        if (idx != NULL) {
            throw new IllegalArgumentException(
                    "Value for key " + key + " already exists = " + value
            );
        }

        idx = allocEntry(hashIndex);

        values[idx] = value;

        putKey(idx, key);
    }

    public boolean put(final K key,
                       final V value) {
        int hashIndex = hashIndex(key);
        int idx = find(hashIndex, key);
        if (idx != NULL) {
            values[idx] = value;
            return (false);
        }
        if (freeHead == NULL) {
            resizeTable(values.length * 2);
            hashIndex = hashIndex(key); // recompute!
        }
        idx = allocEntry(hashIndex);

        values[idx] = value;
        putKey(idx, key);
        return true;
    }

    public boolean containsValue(final V value) {
        final int tabSize = values.length;
        for (int i = 0; i < tabSize; i++) {
            if (isFilled(i) && values[i] == value) {
                return true;
            }
        }
        return false;
    }

    protected class ElementEnumeration implements ResetableEnumeration<V>, KeyEntry<K> {
        private int currentIdx = -1;

        public ElementEnumeration() {
            move();
        }

        private void move() {
            do {
                currentIdx++;
            } while (currentIdx < values.length && isEmpty(currentIdx));
        }

        @Override
        public boolean hasMoreElements() {
            return currentIdx < values.length;
        }

        @Override
        public void reset() {
            currentIdx = -1;
            move();
        }

        @Override
        @SuppressWarnings("unchecked")
        public V nextElement() {
            final V value = (V) values[currentIdx]; // unchecked
            move();
            return value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public K key() {
            return (K) keys[currentIdx]; // unchecked
        }
    }

    public ResetableEnumeration<V> elements() {
        return new ElementEnumeration();
    }

    @Override
    public final boolean equals(final Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int hashCode() {
        throw new UnsupportedOperationException();
    }
}
