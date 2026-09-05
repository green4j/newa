/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

import java.util.Arrays;

/**
 * The index table under the maps of this package: one slot per entry, hash buckets chained by slot index
 * rather than by node, and the slots nobody holds kept on a free list. Nothing is allocated to put an entry
 * in, and the table doubles when the free list runs out.
 * <p>
 * Not a {@link java.util.Map} and not thread-safe. {@link CharSequenceToObjectMapConcurrent} is the one
 * here which is.
 */
public abstract class MapBase {
    protected static final int NULL = Integer.MIN_VALUE;
    /**
     * The smallest table built. A capacity below this is raised to it rather than refused.
     */
    public static final int MIN_CAPACITY = 16;

    protected int count = 0;
    protected int freeHead;
    protected int[] hashIndexes;
    protected int[] next;
    protected int[] prev;

    protected MapBase() {
        this(16);
    }

    @SuppressWarnings("this-escape")
    protected MapBase(final int capacity) {
        int cap = capacity;
        if (cap < MIN_CAPACITY) {
            cap = MIN_CAPACITY;
        }

        allocTable(cap);
    }

    public final int size() {
        return count;
    }

    /**
     * @return slots the table holds, filled or free - what doubles when the map runs out of them
     */
    public final int capacity() {
        return next.length;
    }

    public void clear() {
        format();
    }

    private void format() {
        count = 0;

        Arrays.fill(hashIndexes, NULL);
        Arrays.fill(prev, NULL);

        final int cap = prev.length;

        freeHead = cap - 1;

        next[0] = NULL;

        for (int i = 1; i < cap; i++) {
            next[i] = i - 1;
        }
    }

    protected void allocTable(final int capacity) {
        hashIndexes = new int[capacity];
        next = new int[capacity];
        prev = new int[capacity];

        format();
    }

    protected void free(final int idx) {
        // Remove [idx] from the chain
        final int nx = next[idx];
        final int pv = prev[idx];

        if (nx != NULL) {
            prev[nx] = pv;
        }

        if (pv < 0) {
            hashIndexes[-pv - 1] = nx;
        } else {
            next[pv] = nx;
        }
        // Link [idx] to free list
        next[idx] = freeHead;
        prev[idx] = NULL;  // prev must be NULL in a free list
        freeHead = idx;
        count--;
    }

    protected int allocEntry(final int hidx) {
        final int newChainHeadIdx = freeHead;

        freeHead = next[newChainHeadIdx];

        final int oldChainHeadIdx = hashIndexes[hidx];

        next[newChainHeadIdx] = oldChainHeadIdx;

        if (oldChainHeadIdx != NULL) {
            prev[oldChainHeadIdx] = newChainHeadIdx;
        }

        prev[newChainHeadIdx] = -hidx - 1;
        hashIndexes[hidx] = newChainHeadIdx;

        count++;
        return newChainHeadIdx;
    }

    protected final boolean isFilled(final int idx) {
        return prev[idx] != NULL;
    }

    protected final boolean isEmpty(final int idx) {
        return prev[idx] == NULL;
    }
}