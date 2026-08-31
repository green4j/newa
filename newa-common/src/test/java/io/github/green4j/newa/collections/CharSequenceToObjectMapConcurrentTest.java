package io.github.green4j.newa.collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class CharSequenceToObjectMapConcurrentTest {

    @Test
    public void testPutAndGet() {
        final CharSequenceToObjectMapConcurrent<String> map = new CharSequenceToObjectMapConcurrent<>();

        map.put("AA", "a");
        map.put(new StringBuilder("BB"), "b");

        Assertions.assertEquals("a", map.get("AA"));
        Assertions.assertEquals("a", map.get(new StringBuilder("AA")));
        Assertions.assertEquals("b", map.get("BB"));
        Assertions.assertNull(map.get("CC"));
        Assertions.assertEquals(2, map.size());
    }

    @Test
    public void testGetWithSubsequence() {
        final CharSequenceToObjectMapConcurrent<String> map = new CharSequenceToObjectMapConcurrent<>();
        map.put("AA", "a");

        final CharSequence line = "prefix:AA:suffix";

        Assertions.assertEquals("a", map.get(line, 7, 9));
        Assertions.assertNull(map.get(line, 0, 6));
    }

    @Test
    public void testContainsKeyAndRemove() {
        final CharSequenceToObjectMapConcurrent<String> map = new CharSequenceToObjectMapConcurrent<>();
        map.put("AA", "a");

        Assertions.assertTrue(map.containsKey("AA"));
        Assertions.assertTrue(map.containsKey(new StringBuilder("AA")));
        Assertions.assertFalse(map.containsKey("BB"));

        Assertions.assertEquals("a", map.remove(new StringBuilder("AA")));
        Assertions.assertNull(map.remove("AA"));
        Assertions.assertFalse(map.containsKey("AA"));
        Assertions.assertTrue(map.isEmpty());
    }

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
