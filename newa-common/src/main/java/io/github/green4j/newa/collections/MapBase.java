package io.github.green4j.newa.collections;

import java.util.Arrays;

public abstract class MapBase implements Cloneable {
    protected static final int NULL = Integer.MIN_VALUE;
    public static final int MIN_CAPACITY = 16;

    protected int count = 0;
    protected int freeHead;
    protected int[] hashIndexes;
    protected int[] next;
    protected int[] prev;

    protected MapBase() {
        this(16);
    }

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

    public final int getCapacity() {
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