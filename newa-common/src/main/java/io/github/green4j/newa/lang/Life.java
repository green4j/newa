/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The whole life of one resource: opens it, holds the calling thread until the end is asked for, closes it,
 * and does the same when the JVM is going down. The last line of a {@code main}:
 * <pre>{@code
 * final Life life = new Life();
 *
 * apiBuilder.postJson("/shutdown", new JsonExecute(() -> life.end("Called by REST API")));
 *
 * life.run(() -> RestServer.of(apiBuilder.build()).start(9009));
 * }</pre>
 * A {@link Life} is an {@link Ender} from the moment it is constructed, which is what makes that form work:
 * the endpoint is registered before the api is built, and the server does not exist until after that.
 * <p>
 * The contract, in full:
 * <ul>
 *   <li><b>The resource is opened by {@link #run}, not handed to it</b>, so there is no stretch of caller
 *       code in which it is running and nobody owns it.</li>
 *   <li>One {@link Life} runs one resource, once. A second {@link #run} is an
 *       {@link IllegalStateException}, whether it comes after the first returned or beside it.</li>
 *   <li>{@link #run} <b>blocks</b> until {@link #end}, and closes the resource on the thread which called
 *       it, never on the one which asked for the end: a {@code /shutdown} endpoint runs on an event loop,
 *       and closing a server from one of its own loops makes it wait for a shutdown it is holding up.</li>
 *   <li>{@link #end} is safe from any thread, before {@link #run} as well as during it, and is idempotent.
 *       Asked for before {@link #run}, nothing is opened at all; asked for while the resource is being
 *       opened, it is honoured the instant opening returns.</li>
 *   <li>A resource which can end without being closed says so by being {@link SelfEnding}, and {@link #run}
 *       registers itself with it - which is what keeps a server that died under us from leaving the
 *       process up and parked here.</li>
 *   <li>An {@link Observer} hears {@link Observer#onEnding} before {@link Observer#onEnded}, always.</li>
 *   <li>A JVM shutdown hook is registered for the length of the run and removed afterwards.</li>
 * </ul>
 * One thing no hook can buy: the process halts as soon as the last hook returns and does not wait for
 * {@code main}. The hook here waits for the resource to be closed, but whatever a {@code main} does
 * <i>after</i> {@link #run} returns is racing that halt. Put what has to happen into the resource's own
 * {@code close()}, which is waited for.
 */
public final class Life implements Ender {
    /**
     * How long the shutdown hook waits for the close to finish. The JVM halts as soon as its last hook
     * returns, without waiting for {@code main}, so this is what keeps a graceful close from being cut in
     * half - and it is bounded so that a close which will not finish cannot hold the process open either.
     */
    private static final long CLOSE_TIMEOUT_MILLIS = 10_000L;

    private final CountDownLatch ending = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);

    private Observer observer; // guarded by this
    private String cause; // guarded by this
    private boolean running; // guarded by this
    private boolean endRequested; // guarded by this

    /**
     * Opens what a {@link Life} runs. Called by {@link Life#run}, so whatever it returns is owned from the
     * instant it exists rather than from whenever the caller got round to handing it over.
     */
    public interface Opener {
        /**
         * @return the resource, running. It is closed once the end is asked for.
         * @throws Exception if it could not be opened, which {@link Life#run} passes on unchanged.
         */
        AutoCloseable open() throws Exception;
    }

    /**
     * What a {@link Life} reports, or null to report nothing. Every method has a no-op default, as in the
     * observers of the REST and WebSocket apis.
     */
    public interface Observer {
        /**
         * The resource is open and the calling thread is about to park until the end is asked for.
         */
        default void onRunning() {
        }

        /**
         * The end has been asked for. Runs on whichever thread called {@link Ender#end}, or on the JVM
         * shutdown hook - never on the one parked in {@link Life#run}, which is still parked.
         *
         * @param cause it was given.
         */
        default void onEnding(String cause) {
        }

        /**
         * The resource is closed and {@link Life#run} is about to return.
         */
        default void onEnded() {
        }
    }

    public Life() {
    }

    /**
     * Several resources as one, for a {@link Life} with more than one thing to run:
     * <pre>{@code
     * life.run(Life.all(
     *         () -> RestServer.start(9009, adminApi),
     *         () -> RestServer.start(9010, publicApi)));
     * }</pre>
     * They are <b>opened in the order given</b>, and one which fails to open closes those already open
     * before the failure is passed on - which has to happen here, because until an opener returns a
     * {@link Life} owns nothing and a resource opened beside the failure is one nothing would ever close.
     * <p>
     * They are <b>closed at once</b> rather than one after another, so the worst case is the timeout of one
     * close instead of the sum of all of them, which is what a JVM shutdown hook has to fit into. When the
     * order of closing matters, write an opener which closes them in the order they need and run that.
     * <p>
     * <b>Any one of them ending by itself ends them all</b>: one which is {@link SelfEnding} is registered
     * with the {@link Life} running this.
     *
     * @param openers of the resources, in the order they are to be opened. At least one.
     * @return one opener of all of them, for {@link #run}.
     * @throws IllegalArgumentException if not given an opener.
     */
    public static Opener all(final Opener... openers) {
        if (openers == null || openers.length == 0) {
            throw new IllegalArgumentException("At least one opener is required");
        }

        final Opener[] toOpen = openers.clone(); // the caller's array is not ours to be surprised by

        return () -> {
            final AutoCloseable[] opened = new AutoCloseable[toOpen.length];
            int count = 0;
            try {
                for (int i = 0; i < toOpen.length; i++) {
                    opened[i] = toOpen[i].open();
                    count = i + 1;
                }
            } catch (final Exception failed) {
                closeAll(opened, count); // opened, owned by nobody, and this is the last chance to close
                throw failed;
            }

            return new Opened(opened);
        };
    }

    /**
     * What {@link #all} opened, as the one resource a {@link Life} runs - which is why the registering of
     * an {@link Ender} has to be passed on: the {@link Life} sees this and never the resources themselves.
     */
    private static final class Opened implements SelfEnding {
        private final AutoCloseable[] resources;

        private Opened(final AutoCloseable[] resources) {
            this.resources = resources;
        }

        @Override
        public void whenEnded(final Ender ender) {
            for (int i = 0; i < resources.length; i++) {
                if (resources[i] instanceof SelfEnding) {
                    ((SelfEnding) resources[i]).whenEnded(ender);
                }
            }
        }

        @Override
        public void close() {
            closeAll(resources, resources.length);
        }
    }

    /**
     * @param opener of the resource to run.
     * @throws Exception whatever the opener threw, or InterruptedException if the calling thread is
     *                   interrupted while waiting.
     */
    public void run(final Opener opener) throws Exception {
        run(opener, null);
    }

    /**
     * @param opener of the resource to run.
     * @param observer told about this run, or null to report nothing.
     * @throws Exception whatever the opener threw, or InterruptedException if the calling thread is
     *                   interrupted while waiting.
     */
    public void run(final Opener opener,
                    final Observer observer) throws Exception {
        final Thread hook = new Thread(() -> {
            end("Process termination happened");
            awaitEnded();
        });

        AutoCloseable opened = null;
        try {
            final boolean endedBeforeOpening;
            final String reasonBeforeOpening;

            synchronized (this) {
                if (running) {
                    throw new IllegalStateException("Running already: one Life runs one resource, once");
                }
                running = true;
                endedBeforeOpening = endRequested;
                reasonBeforeOpening = cause;
            }

            if (endedBeforeOpening) {
                // asked to end before anything was opened, so nothing is opened at all
                if (observer != null) {
                    observer.onEnding(reasonBeforeOpening);
                }
                return;
            }

            // owned from the instant it exists: nothing of the caller's runs between opening the resource
            // and this Life having it, which is what leaves no window
            opened = opener.open();

            if (opened instanceof SelfEnding) {
                // one which can die under us ends this Life itself, rather than leaving the thread below
                // parked on an end nobody is left to ask for. An end which happened while it was being
                // opened is not lost by registering only now - it is reported the moment we ask
                ((SelfEnding) opened).whenEnded(this);
            }

            final boolean endedWhileOpening;
            final String reasonWhileOpening;
            synchronized (this) {
                endedWhileOpening = endRequested;
                reasonWhileOpening = cause;
                if (!endedWhileOpening) {
                    // the observer is registered exactly here, and not before: until this point end() has
                    // nobody to tell and the reporting below is ours, from here on it is end()'s. That
                    // hand-off under the lock is what makes onEnding fire once and not twice
                    this.observer = observer;
                }
            }

            if (endedWhileOpening) {
                // asked for while the resource was being opened, when nobody could have closed it yet
                if (observer != null) {
                    observer.onEnding(reasonWhileOpening);
                }
                return;
            }

            Runtime.getRuntime().addShutdownHook(hook);
            try {
                if (observer != null) {
                    observer.onRunning();
                }
                ending.await();
            } finally {
                removeShutdownHook(hook);
            }
        } finally {
            try {
                CloseHelper.closeQuiet(opened);
                if (observer != null) {
                    observer.onEnded();
                }
            } finally {
                ended.countDown(); // releases the shutdown hook, if that is what ended us
            }
        }
    }

    @Override
    public void end(final String cause) {
        final Observer toTell;

        synchronized (this) {
            if (endRequested) {
                return;
            }
            endRequested = true;
            this.cause = cause;
            toTell = observer; // null while run has not been reached
        }

        try {
            if (toTell != null) {
                toTell.onEnding(cause);
            }
        } finally {
            ending.countDown();
        }
    }

    /**
     * Closes the first {@code count} of them at once: one on this thread, the rest on a thread each, and
     * returns when every one of them is closed. Closing is never abandoned half done - an interrupt is
     * remembered and re-asserted afterwards - because what waits for this is a {@link Life#run} which
     * reports the resource closed the moment it returns.
     *
     * @param resources to close, some of which may be null - an opener may return one.
     * @param count     of them to close, which is how many were opened before a failure.
     */
    private static void closeAll(final AutoCloseable[] resources,
                                 final int count) {
        if (count < 2) {
            if (count == 1) {
                CloseHelper.closeQuiet(resources[0]);
            }
            return;
        }

        final Thread[] closers = new Thread[count - 1];
        for (int i = 1; i < count; i++) {
            final AutoCloseable resource = resources[i];
            final Thread closer = new Thread(
                    () -> CloseHelper.closeQuiet(resource),
                    "life-closer-" + i
            );
            closers[i - 1] = closer;
            closer.start();
        }

        CloseHelper.closeQuiet(resources[0]); // this thread closes one of them rather than only waiting

        boolean interrupted = false;
        for (int i = 0; i < closers.length; i++) {
            while (true) {
                try {
                    closers[i].join();
                    break;
                } catch (final InterruptedException interruptedWhileClosing) {
                    interrupted = true;
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitEnded() {
        try {
            ended.await(CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void removeShutdownHook(final Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (final IllegalStateException goingDown) {
            // the JVM is already running its hooks, and this one is why we are here: nothing to remove
        }
    }
}
