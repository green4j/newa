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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The whole life of one resource: opens it, holds the calling thread until the end is asked for, closes it,
 * and does the same when the JVM is going down. This is the last line of a {@code main}:
 * <pre>{@code
 * new Life().run(() -> RestServer.start(9009, api));
 * }</pre>
 * and the shape of one which can also be ended from the outside:
 * <pre>{@code
 * final Life life = new Life();
 *
 * apiBuilder.postJson("/shutdown", new JsonExecute(() -> life.end("Called by REST API")));
 *
 * life.run(() -> RestServer.of(apiBuilder.build()).start(9009));
 * }</pre>
 * A {@link Life} is an {@link Ender} from the moment it is constructed, which is what makes the second form
 * work at all: the endpoint has to be registered before the api is built, and the server does not exist
 * until after that, so nothing else is available for the handle to hold.
 * <p>
 * <b>The resource is opened by {@link #run}, not handed to it.</b> That is what leaves no window: there is
 * no moment at which the thing is running and nobody owns it. Handing over an already-running resource
 * would leave the statements between starting it and waiting for it - however few - as a stretch in which
 * an end asked for is an end nobody carries out.
 * <p>
 * The contract, in full:
 * <ul>
 *   <li>One {@link Life} runs one resource, once. A second {@link #run} is an
 *       {@link IllegalStateException}, whether it comes after the first returned or beside it.</li>
 *   <li>{@link #run} <b>blocks</b> until {@link #end}, and only then closes the resource - on the thread
 *       which called it, never on whichever thread asked for the end. That is deliberate: a
 *       {@code /shutdown} endpoint runs on an event loop, and closing a server from one of its own event
 *       loops makes it wait for a shutdown it is itself holding up.</li>
 *   <li>{@link #end} is safe from any thread, before {@link #run} as well as during it, and is idempotent.
 *       Asked for before {@link #run}, nothing is opened at all; asked for while the resource is being
 *       opened, it is honoured the instant opening returns.</li>
 *   <li>An {@link Observer} hears {@link Observer#onEnding} before {@link Observer#onEnded}, always.</li>
 * </ul>
 * Nothing here knows what the resource is - opening and closing it is all that is asked - so a {@link Life}
 * is also the whole of the JVM shutdown hook: it registers one for the length of the run, and removes it
 * afterwards.
 * <p>
 * One thing that hook cannot buy, because no hook can: when the end came from the JVM going down, the
 * process halts as soon as the last hook returns, and it does not wait for {@code main}. The hook here waits
 * for the resource to be closed, so that much is safe - but whatever a {@code main} does <i>after</i>
 * {@link #run} returns is racing the halt and may not run. Put anything that has to happen into the
 * resource's own {@code close()}, which is waited for, rather than after the run.
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
     * Several resources as one, for a {@link Life} which has more than one thing to run - two servers on
     * two ports, say:
     * <pre>{@code
     * life.run(Life.all(
     *         () -> RestServer.start(9009, adminApi),
     *         () -> RestServer.start(9010, publicApi)));
     * }</pre>
     * They are <b>opened in the order given</b>, and one which fails to open closes those already open
     * before the failure is passed on. That undoing has to happen here and nowhere else: until an opener
     * returns, a {@link Life} owns nothing, so a resource opened beside one which then failed is a
     * resource nothing else would ever close.
     * <p>
     * They are <b>closed at once</b> rather than one after another, and the close returns when the last
     * of them is closed. Resources which do not depend on each other lose nothing by that - two servers
     * do not, each owning the event loops it shuts down - and it keeps the worst case at the timeouts of
     * one close instead of the sum of all of them, which is what a JVM shutdown hook has to fit into.
     * When the order of closing does matter, this is the wrong tool: write an opener which closes them in
     * the order they need, and let the {@link Life} run that.
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

            return () -> closeAll(opened, opened.length);
        };
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
