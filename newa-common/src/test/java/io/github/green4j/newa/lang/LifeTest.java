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

package io.github.green4j.newa.lang;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class LifeTest {
    private static final long TIMEOUT_MILLIS = 5_000L;

    /**
     * Life knows nothing about what it runs, so neither does this.
     */
    private static final class Resource implements AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class Throwing implements AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() throws Exception {
            closes.incrementAndGet();
            throw new Exception("this close always fails");
        }
    }

    private static final class Recording implements Life.Observer {
        private final List<String> stages = new CopyOnWriteArrayList<>();

        @Override
        public void onRunning() {
            stages.add("running");
        }

        @Override
        public void onEnding(final String cause) {
            stages.add("ending:" + cause);
        }

        @Override
        public void onEnded() {
            stages.add("ended");
        }
    }

    private static final class Running {
        private final Thread thread;
        private final CountDownLatch returned = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Running(final Life life,
                        final Life.Opener opener,
                        final Life.Observer observer) {
            thread = new Thread(() -> {
                try {
                    life.run(opener, observer);
                } catch (final Throwable t) {
                    failure.set(t);
                } finally {
                    returned.countDown();
                }
            });
        }

        private void start() throws InterruptedException {
            thread.start();
            // give it a moment to reach the park, so a test which ends afterwards really tests that path
            Thread.sleep(100L);
        }

        private boolean returnedWithin(final long millis) throws InterruptedException {
            return returned.await(millis, TimeUnit.MILLISECONDS);
        }
    }

    private static Running runInBackground(final Life life,
                                           final AutoCloseable resource,
                                           final Life.Observer observer) throws InterruptedException {
        final Running running = new Running(life, () -> resource, observer);
        running.start();
        return running;
    }

    @Test
    public void endReleasesTheThreadRunning() throws Exception {
        final Life life = new Life();
        final Resource resource = new Resource();
        final Recording observer = new Recording();

        final Running running = runInBackground(life, resource, observer);
        Assertions.assertFalse(running.returnedWithin(100L), "returned before it was ended");

        life.end("by test");

        Assertions.assertTrue(running.returnedWithin(TIMEOUT_MILLIS), "still running after end()");
        Assertions.assertNull(running.failure.get());
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(List.of("running", "ending:by test", "ended"), observer.stages);
    }

    @Test
    public void endBeforeRunOpensNothingAtAll() throws Exception {
        final Life life = new Life();
        final AtomicBoolean opened = new AtomicBoolean();
        final Recording observer = new Recording();

        life.end("early");

        life.run(() -> {
            opened.set(true);
            return new Resource();
        }, observer);

        // nothing was started, so there is nothing to have leaked - this is the window closed by
        // construction rather than handled after the fact
        Assertions.assertFalse(opened.get(), "opened a resource it had already been told to end");
        Assertions.assertEquals(List.of("ending:early", "ended"), observer.stages);
    }

    @Test
    public void endWhileOpeningIsHonouredAsSoonAsOpeningReturns() throws Exception {
        // the window a /shutdown endpoint lives in: the resource is already serving and the end is asked
        // for before run() has it in hand. Nothing of the caller's runs in between, so it is still closed.
        final Life life = new Life();
        final Resource resource = new Resource();
        final Recording observer = new Recording();

        life.run(() -> {
            life.end("while opening");   // as a request served by the thing being opened would
            return resource;
        }, observer);

        Assertions.assertEquals(1, resource.closes.get(), "the resource opened was not closed");
        Assertions.assertEquals(List.of("ending:while opening", "ended"), observer.stages);
    }

    @Test
    public void endIsIdempotent() throws Exception {
        final Life life = new Life();
        final Resource resource = new Resource();
        final Recording observer = new Recording();

        final Running running = runInBackground(life, resource, observer);

        life.end("first");
        life.end("second");
        life.end("third");

        Assertions.assertTrue(running.returnedWithin(TIMEOUT_MILLIS));
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(List.of("running", "ending:first", "ended"), observer.stages);
    }

    @Test
    public void endIsSafeBeforeAnythingAtAll() {
        final Life life = new Life();

        Assertions.assertDoesNotThrow(() -> life.end("nothing to end yet"));
        Assertions.assertDoesNotThrow(() -> life.end("still nothing"));
    }

    @Test
    public void oneLifeRunsOneResourceOnce() throws Exception {
        final Life life = new Life();

        life.end("done");
        life.run(Resource::new);

        Assertions.assertThrows(
                IllegalStateException.class,
                () -> life.run(Resource::new)
        );
    }

    @Test
    public void aSecondRunBesideTheFirstIsRefused() throws Exception {
        final Life life = new Life();
        final Resource resource = new Resource();

        final Running running = runInBackground(life, resource, null);
        try {
            Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> life.run(Resource::new)
            );
        } finally {
            life.end("done");
            Assertions.assertTrue(running.returnedWithin(TIMEOUT_MILLIS));
        }
    }

    @Test
    public void whatTheOpenerThrowsReachesTheCaller() {
        final Life life = new Life();
        final Recording observer = new Recording();

        final Exception thrown = Assertions.assertThrows(
                Exception.class,
                () -> life.run(() -> {
                    throw new IllegalArgumentException("could not bind");
                }, observer)
        );

        Assertions.assertEquals("could not bind", thrown.getMessage());
        Assertions.assertEquals(List.of("ended"), observer.stages);
    }

    @Test
    public void theResourceIsClosedEvenWhenItsCloseThrows() throws Exception {
        final Life life = new Life();
        final Throwing resource = new Throwing();
        final Recording observer = new Recording();

        life.run(() -> {
            life.end("done");
            return resource;
        }, observer);

        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertTrue(observer.stages.contains("ended"), "not reported ended: " + observer.stages);
    }

    @Test
    public void noObserverIsFine() throws Exception {
        final Life withoutOne = new Life();
        final Resource first = new Resource();
        withoutOne.run(() -> {
            withoutOne.end("done");
            return first;
        });
        Assertions.assertEquals(1, first.closes.get());

        final Life withNull = new Life();
        final Resource second = new Resource();
        withNull.run(() -> {
            withNull.end("done");
            return second;
        }, null);
        Assertions.assertEquals(1, second.closes.get());
    }
}
