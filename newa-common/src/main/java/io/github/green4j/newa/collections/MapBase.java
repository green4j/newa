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

public abstract class MapBase {
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