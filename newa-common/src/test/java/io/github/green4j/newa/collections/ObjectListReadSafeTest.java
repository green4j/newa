/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ObjectListReadSafeTest {
    private static List<String> itemsOf(final ObjectListReadSafe<String> list) {
        final ObjectListReadSafe.Snapshot<String> snapshot = list.snapshot();

        final List<String> result = new ArrayList<>();
        for (int i = 0; i < snapshot.limit(); i++) {
            final String item = snapshot.get(i);
            if (item == null) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    @Test
    public void testAddAndWalk() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        Assertions.assertEquals(0, list.size());
        Assertions.assertEquals(0, list.snapshot().limit());

        list.add("a");
        list.add("b");

        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals(List.of("a", "b"), itemsOf(list));
    }

    @Test
    public void testASnapshotNeverSeesWhatWasAddedAfterIt() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        list.add("a");

        final ObjectListReadSafe.Snapshot<String> before = list.snapshot();

        for (int i = 0; i < 100; i++) { // enough to grow the array underneath it more than once
            list.add("added-" + i);
        }

        Assertions.assertEquals(1, before.limit());
        Assertions.assertEquals(1, before.size());
        Assertions.assertEquals("a", before.get(0));

        Assertions.assertEquals(101, list.size());
    }

    @Test
    public void testRemoveLeavesAHoleAndThenCollectsIt() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        list.add("a");
        list.add("b");
        list.add("c");
        list.add("d");

        Assertions.assertTrue(list.remove("b"));
        Assertions.assertFalse(list.remove("b"));

        Assertions.assertEquals(3, list.size());
        Assertions.assertEquals(4, list.snapshot().limit(), "the hole is still where the item was");
        Assertions.assertNull(list.snapshot().get(1));
        Assertions.assertEquals(List.of("a", "c", "d"), itemsOf(list));

        Assertions.assertTrue(list.remove("a"));
        Assertions.assertTrue(list.remove("c")); // one item left of four: the holes are collected

        Assertions.assertEquals(1, list.size());
        Assertions.assertEquals(1, list.snapshot().limit());
        Assertions.assertEquals(List.of("d"), itemsOf(list));
    }

    @Test
    public void testTheSlotOfARemovedItemIsNotHandedToAnotherOne() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        list.add("a");
        list.add("b");

        list.remove("a");

        final ObjectListReadSafe.Snapshot<String> before = list.snapshot();

        list.add("c");

        Assertions.assertNull(before.get(0), "the hole stays a hole for whoever took the snapshot");
        Assertions.assertEquals(List.of("b", "c"), itemsOf(list));
    }

    @Test
    public void testGrowsAndKeepsEverything() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();

        final List<String> added = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            final String item = "item-" + i;
            added.add(item);
            list.add(item);
        }

        Assertions.assertEquals(1000, list.size());
        Assertions.assertEquals(added, itemsOf(list));

        for (int i = 0; i < 1000; i++) {
            Assertions.assertTrue(list.contains(added.get(i)));
        }
    }

    @Test
    public void testContainsIsByIdentity() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();

        final String item = new String("a");
        final String equal = new String("a");

        list.add(item);

        Assertions.assertTrue(list.contains(item));
        Assertions.assertFalse(list.contains(equal));
        Assertions.assertFalse(list.remove(equal));
    }

    @Test
    public void testRejectsNull() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        Assertions.assertThrows(IllegalArgumentException.class, () -> list.add(null));
    }

    @Test
    public void testClearHandsBackWhatItTookAndStaysUsable() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        list.add("a");
        list.add("b");

        final ObjectListReadSafe.Snapshot<String> taken = list.clear();

        Assertions.assertEquals(2, taken.size());
        Assertions.assertEquals("a", taken.get(0));
        Assertions.assertEquals("b", taken.get(1));

        Assertions.assertEquals(0, list.size());

        list.add("c");
        Assertions.assertEquals(List.of("c"), itemsOf(list));
        Assertions.assertEquals(2, taken.size(), "what was taken is nobody's to change");
    }

    @Test
    public void testCloseIsTerminalAndIdempotent() {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();
        list.add("a");

        final ObjectListReadSafe.Snapshot<String> taken = list.close();
        Assertions.assertEquals(1, taken.size());

        Assertions.assertEquals(0, list.close().size(), "the first close took everything");
        Assertions.assertEquals(0, list.size());
        Assertions.assertFalse(list.remove("a"));

        Assertions.assertFalse(list.add("b"), "a closed list says no rather than throwing");
        Assertions.assertEquals(0, list.size());
        Assertions.assertFalse(list.contains("b"));

        Assertions.assertThrows(IllegalArgumentException.class, () -> list.add(null));
    }

    @Test
    public void testWalkingWhileItIsChanged() throws Exception {
        final ObjectListReadSafe<String> list = new ObjectListReadSafe<>();

        final int writers = 4;
        final int perWriter = 2000;

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(writers);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        final List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            final Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        final String item = "w" + writer + '-' + i;
                        list.add(item);
                        if ((i & 1) == 0) {
                            list.remove(item); // half of them leave holes behind
                        }
                    }
                } catch (final Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        final Thread reader = new Thread(() -> {
            try {
                start.await();
                while (done.getCount() > 0) {
                    final ObjectListReadSafe.Snapshot<String> snapshot = list.snapshot();
                    int seen = 0;
                    for (int i = 0; i < snapshot.limit(); i++) {
                        if (snapshot.get(i) != null) {
                            seen++;
                        }
                    }
                    if (seen > snapshot.limit()) {
                        throw new AssertionError("walked more items than the snapshot holds");
                    }
                }
            } catch (final Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
        reader.start();

        start.countDown();
        Assertions.assertTrue(done.await(30, TimeUnit.SECONDS));
        reader.join(TimeUnit.SECONDS.toMillis(30));
        for (int i = 0; i < threads.size(); i++) {
            threads.get(i).join(TimeUnit.SECONDS.toMillis(30));
        }

        Assertions.assertNull(failure.get(), () -> String.valueOf(failure.get()));

        Assertions.assertEquals(writers * perWriter / 2, list.size());

        final List<String> left = itemsOf(list);
        Assertions.assertEquals(writers * perWriter / 2, left.size(), "no item may be lost or duplicated");
        for (int i = 0; i < left.size(); i++) {
            Assertions.assertTrue(list.contains(left.get(i)));
        }
    }
}
