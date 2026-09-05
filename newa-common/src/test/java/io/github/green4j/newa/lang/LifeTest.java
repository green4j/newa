/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
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

    /**
     * One which can die under whoever runs it, as a bound server can: it holds what it was registered with
     * and ends when the test says it has.
     */
    private static final class Dying implements SelfEnding {
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicReference<Ender> ender = new AtomicReference<>();
        private final String cause;
        private final boolean endedAlready;

        private Dying(final String cause,
                      final boolean endedAlready) {
            this.cause = cause;
            this.endedAlready = endedAlready;
        }

        @Override
        public void whenEnded(final Ender toTell) {
            ender.set(toTell);
            if (endedAlready) {
                toTell.end(cause);   // as a listener added to a channel already closed is called at once
            }
        }

        private void die() {
            ender.get().end(cause);
        }

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

    @Test
    public void allOpensInTheOrderGivenAndClosesEveryOneOfThem() throws Exception {
        final Life life = new Life();
        final List<String> opened = new CopyOnWriteArrayList<>();
        final Resource first = new Resource();
        final Resource second = new Resource();

        life.run(Life.all(
                () -> {
                    opened.add("first");
                    return first;
                },
                () -> {
                    opened.add("second");
                    life.end("done");
                    return second;
                }));

        Assertions.assertEquals(List.of("first", "second"), opened);
        Assertions.assertEquals(1, first.closes.get());
        Assertions.assertEquals(1, second.closes.get());
    }

    @Test
    public void allUndoesWhatItOpenedWhenOneCannotBeOpened() {
        final Life life = new Life();
        final Resource first = new Resource();
        final AtomicBoolean thirdOpened = new AtomicBoolean();

        final Exception thrown = Assertions.assertThrows(Exception.class, () ->
                life.run(Life.all(
                        () -> first,
                        () -> {
                            throw new Exception("could not bind");
                        },
                        () -> {
                            thirdOpened.set(true);
                            return new Resource();
                        })));

        Assertions.assertEquals("could not bind", thrown.getMessage());
        Assertions.assertEquals(1, first.closes.get(), "the one already open was left open");
        Assertions.assertFalse(thirdOpened.get(), "opening carried on past the failure");
    }

    @Test
    public void allClosesEveryResourceAtOnce() throws Exception {
        final Life life = new Life();
        final CountDownLatch closing = new CountDownLatch(2);
        final AtomicBoolean sawTheOther = new AtomicBoolean(true);

        // one closed after the other would never see the second one arrive here, so a sequential close
        // fails this rather than merely being slower than it could be
        final Life.Opener waitingForTheOther = () -> () -> {
            closing.countDown();
            if (!closing.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                sawTheOther.set(false);
            }
        };

        life.run(Life.all(
                waitingForTheOther,
                () -> {
                    life.end("done");
                    return waitingForTheOther.open();
                }));

        Assertions.assertTrue(sawTheOther.get(), "the two closes did not overlap");
    }

    @Test
    public void aResourceWhichEndsByItselfEndsTheLife() throws Exception {
        // the thread in run() waits for end() and for nothing else, so a resource which died under it has
        // to be the one to say so - or the process stays up owning something which is no longer running
        final Life life = new Life();
        final Dying resource = new Dying("Port 9009 closed", false);
        final Recording observer = new Recording();

        final Running running = runInBackground(life, resource, observer);
        Assertions.assertFalse(running.returnedWithin(100L), "returned before anything ended");

        resource.die();

        Assertions.assertTrue(running.returnedWithin(TIMEOUT_MILLIS), "still running after the resource died");
        Assertions.assertNull(running.failure.get());
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(
                List.of("running", "ending:Port 9009 closed", "ended"),
                observer.stages);
    }

    @Test
    public void anEndWhichHappenedBeforeAnybodyWatchedIsNotLost() throws Exception {
        // a channel closed between binding and being asked about reports at once, from inside whenEnded,
        // which lands the end before run() has parked. It is honoured there rather than waited for
        final Life life = new Life();
        final Dying resource = new Dying("Port 9009 closed", true);
        final Recording observer = new Recording();

        life.run(() -> resource, observer);

        Assertions.assertEquals(1, resource.closes.get(), "the resource opened was not closed");
        Assertions.assertEquals(List.of("ending:Port 9009 closed", "ended"), observer.stages);
    }

    @Test
    public void anyOneOfAllEndingByItselfEndsThemAll() throws Exception {
        // half of what was promised is not something to stay up serving
        final Life life = new Life();
        final Dying first = new Dying("Port 9009 closed", false);
        final Resource second = new Resource();
        final Dying third = new Dying("Port 9011 closed", false);
        final Recording observer = new Recording();

        final Running running = new Running(life, Life.all(() -> first, () -> second, () -> third), observer);
        running.start();

        third.die();

        Assertions.assertTrue(running.returnedWithin(TIMEOUT_MILLIS), "still running after one of them died");
        Assertions.assertEquals(1, first.closes.get());
        Assertions.assertEquals(1, second.closes.get());
        Assertions.assertEquals(1, third.closes.get());
        Assertions.assertEquals(
                List.of("running", "ending:Port 9011 closed", "ended"),
                observer.stages);
    }

    @Test
    public void allNeedsAtLeastOneOpener() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> Life.all());
    }
}
