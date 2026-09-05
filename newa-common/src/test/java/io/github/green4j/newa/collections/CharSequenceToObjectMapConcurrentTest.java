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

/**
 * What belongs to the concurrent map alone. The CharSequence-keyed contract both maps share is asked of
 * this one too, in {@link CharSequenceToObjectMapTest}.
 */
class CharSequenceToObjectMapConcurrentTest {

    @Test
    public void testPutIfAbsent() {
        final CharSequenceToObjectMapConcurrent<String> map = new CharSequenceToObjectMapConcurrent<>();

        Assertions.assertNull(map.putIfAbsent(new StringBuilder("AA"), "a"));
        Assertions.assertEquals("a", map.putIfAbsent(new StringBuilder("AA"), "b"));
        Assertions.assertEquals("a", map.get("AA"));
    }

    @Test
    public void testReadWhileItIsWritten() throws Exception {
        final CharSequenceToObjectMapConcurrent<String> map = new CharSequenceToObjectMapConcurrent<>();

        final int writers = 4;
        final int perWriter = 5000;

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
                        map.put(new StringBuilder("w").append(writer).append('-').append(i), "v" + i);
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
                final StringBuilder key = new StringBuilder();
                while (done.getCount() > 0) {
                    key.setLength(0);
                    key.append("w0-").append(0);
                    map.get(key); // may or may not be there yet, but must never fail
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
        Assertions.assertEquals(writers * perWriter, map.size());

        for (int w = 0; w < writers; w++) {
            for (int i = 0; i < perWriter; i += 500) {
                Assertions.assertEquals("v" + i, map.get("w" + w + '-' + i));
            }
        }
    }
}
