/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

/**
 * A list of references which is walked by index while it is being changed - without a lock, and without
 * allocating anything to walk it.
 *
 * <p>The point is what it does not do: it never copies itself to add or to remove one item. An addition
 * writes into a slot no reader can see yet and then publishes it; a removal leaves a hole behind. The holes
 * are collected only when there are more of them than there are items, into an array of their own, so a
 * reader holding a {@link Snapshot} is never affected by any of it. That is what turns a storm of clients
 * arriving and leaving from quadratic into linear, and what keeps it from producing garbage the size of the
 * list on every change.
 *
 * <p>A reader takes a {@link #snapshot()} - the same object until something changes, so taking one allocates
 * nothing - and walks it by index, skipping the holes:
 *
 * <pre>
 * final ObjectListReadSafe.Snapshot&lt;T&gt; snapshot = list.snapshot();
 * for (int i = 0; i &lt; snapshot.limit(); i++) {
 *     final T item = snapshot.get(i);
 *     if (item == null) {
 *         continue;
 *     }
 *     // ...
 * }
 * </pre>
 *
 * <p>Two things a reader is promised, and an addition writing out of its sight is what buys both: an item
 * added after the snapshot was taken is never seen through it, and an item which is in it is seen at most
 * once. An item removed after it was taken may still be seen - a walk racing a removal is a race the caller
 * has either way.
 *
 * <p>Items are held and compared by identity, not by {@code equals}. Null is what a hole is, so it is not an
 * item. Changes are serialized with each other by the lock of the list itself and readers take no lock at
 * all; {@link #remove} and {@link #contains} scan, so they cost the length of the list, but they allocate
 * nothing on the way.
 *
 * @param <T> the type of the items.
 */
public class ObjectListReadSafe<T> {
    private static final Object[] EMPTY_ITEMS = new Object[0];
    private static final int MIN_CAPACITY = 8;

    /**
     * The items of a list as they were at one moment. Immutable, and shared by every reader until the list
     * changes.
     *
     * @param <T> the type of the items.
     */
    public static final class Snapshot<T> {
        private final Object[] items;
        private final int limit;
        private final int size;

        private Snapshot(final Object[] items,
                         final int limit,
                         final int size) {
            this.items = items;
            this.limit = limit;
            this.size = size;
        }

        /**
         * @return the index to walk up to. Some of the slots below it may be holes.
         */
        public int limit() {
            return limit;
        }

        /**
         * @return the number of the items in it, which is {@link #limit()} less the holes.
         */
        public int size() {
            return size;
        }

        /**
         * @param index to read, below {@link #limit()}.
         * @return the item at the index, null if that slot is a hole.
         */
        @SuppressWarnings("unchecked")
        public T get(final int index) {
            return (T) items[index];
        }
    }

    private static <T> int indexOf(final Snapshot<T> snapshot,
                                   final T item) {
        final Object[] items = snapshot.items;
        final int limit = snapshot.limit;
        for (int i = 0; i < limit; i++) {
            if (items[i] == item) {
                return i;
            }
        }
        return -1;
    }

    private static int copyItems(final Snapshot<?> from,
                                 final Object[] to) {
        final Object[] items = from.items;
        final int limit = from.limit;
        int copied = 0;
        for (int i = 0; i < limit; i++) {
            final Object item = items[i];
            if (item != null) {
                to[copied++] = item;
            }
        }
        return copied;
    }

    private final Snapshot<T> empty = new Snapshot<>(EMPTY_ITEMS, 0, 0);

    private volatile Snapshot<T> current = empty; // read by multiple threads without a lock,
    // written under the lock of this list only

    private boolean closed; // guarded by this

    public ObjectListReadSafe() {
    }

    /**
     * @return the items as they are right now, to be walked by index.
     */
    public final Snapshot<T> snapshot() {
        return current;
    }

    /**
     * @return the number of the items in the list.
     */
    public final int size() {
        return current.size;
    }

    /**
     * @param item to look for, by identity.
     * @return true if the list holds it.
     */
    public final boolean contains(final T item) {
        return indexOf(current, item) >= 0;
    }

    /**
     * Adds the item, which no snapshot taken before this call ever sees. A closed list says no rather than
     * throwing, the way {@link #remove} says it held nothing: closing is what a caller races, not what it
     * does wrong.
     *
     * @param item to add.
     * @return true if the item was added, false if the list has been closed and holds nothing ever again.
     */
    public final boolean add(final T item) {
        if (item == null) {
            throw new IllegalArgumentException("An item can not be null");
        }

        synchronized (this) {
            if (closed) {
                return false;
            }

            final Snapshot<T> snapshot = current;

            if (snapshot.limit < snapshot.items.length) {
                // the slot is past the limit of every snapshot handed out so far, so nobody can be
                // reading it, and publishing the new snapshot below is what makes the write visible
                snapshot.items[snapshot.limit] = item;
                current = new Snapshot<>(snapshot.items, snapshot.limit + 1, snapshot.size + 1);
                return true;
            }

            // no room left, so an array of its own - which the readers of the current one never see, and
            // which the holes are left out of on the way
            final Object[] items = new Object[Math.max(MIN_CAPACITY, snapshot.size * 2)];
            final int copied = copyItems(snapshot, items);
            items[copied] = item;

            current = new Snapshot<>(items, copied + 1, copied + 1);
            return true;
        }
    }

    /**
     * Removes the item, leaving a hole where it was. The holes are collected once there are more of them
     * than there are items left.
     *
     * @param item to remove, by identity.
     * @return true if the list held it.
     */
    public final boolean remove(final T item) {
        synchronized (this) {
            final Snapshot<T> snapshot = current;

            final int index = indexOf(snapshot, item);
            if (index < 0) {
                return false;
            }

            snapshot.items[index] = null; // a hole, which a reader of any snapshot simply skips
            final int size = snapshot.size - 1;

            if (size * 2 < snapshot.limit) { // more holes than items: collect them
                final Object[] items = new Object[Math.max(MIN_CAPACITY, size * 2)];
                final int copied = copyItems(snapshot, items);
                current = new Snapshot<>(items, copied, copied);
                return true;
            }

            current = new Snapshot<>(snapshot.items, snapshot.limit, size);
            return true;
        }
    }

    /**
     * Takes everything out of the list, which stays usable.
     *
     * @return what was in it, to be walked by index. Nothing changes it afterwards.
     */
    public final Snapshot<T> clear() {
        synchronized (this) {
            final Snapshot<T> snapshot = current;
            current = empty;
            return snapshot;
        }
    }

    /**
     * Takes everything out of the list and refuses to hold anything ever again. Idempotent: the second call
     * hands back nothing, because the first one took it.
     *
     * @return what was in it, to be walked by index. Nothing changes it afterwards.
     */
    public final Snapshot<T> close() {
        synchronized (this) {
            if (closed) {
                return empty;
            }
            closed = true;

            final Snapshot<T> snapshot = current;
            current = empty;
            return snapshot;
        }
    }
}
