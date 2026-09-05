/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

class CloseHelperTest {

    @Test
    public void testCloseQuietNull() {
        Assertions.assertDoesNotThrow(() -> CloseHelper.closeQuiet(null));
    }

    @Test
    public void testCloseQuietCallsClose() {
        final AtomicInteger closed = new AtomicInteger();
        final AutoCloseable resource = closed::incrementAndGet;
        CloseHelper.closeQuiet(resource);
        Assertions.assertEquals(1, closed.get());
    }

    @Test
    public void testCloseQuietSwallowsException() {
        final AutoCloseable throwing = () -> {
            throw new RuntimeException("boom");
        };
        Assertions.assertDoesNotThrow(() -> CloseHelper.closeQuiet(throwing));
    }

    /**
     * Every form of closeQuietAll, each handed the same two resources with a null between them.
     *
     * @return one case per form: what it is called, how it is called, and how many closes it must make.
     */
    private static Stream<Arguments> allTheFormsOfCloseQuietAll() {
        return Stream.of(
                Arguments.of("a collection",
                        (Consumer<AutoCloseable[]>) given ->
                                CloseHelper.closeQuietAll(Arrays.asList(given[0], null, given[1])), 2),
                Arguments.of("varargs",
                        (Consumer<AutoCloseable[]>) given ->
                                CloseHelper.closeQuietAll(given[0], null, given[1]), 2),
                Arguments.of("a collection which is null itself",
                        (Consumer<AutoCloseable[]>) given ->
                                CloseHelper.closeQuietAll((Collection<AutoCloseable>) null), 0),
                Arguments.of("varargs which are null themselves",
                        (Consumer<AutoCloseable[]>) given ->
                                CloseHelper.closeQuietAll((AutoCloseable[]) null), 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allTheFormsOfCloseQuietAll")
    public void closeQuietAllClosesWhatIsThereAndSkipsWhatIsNot(final String form,
                                                                final Consumer<AutoCloseable[]> closeAll,
                                                                final int expected) {
        final AtomicInteger count = new AtomicInteger();
        final AutoCloseable[] resources = {count::incrementAndGet, count::incrementAndGet};

        Assertions.assertDoesNotThrow(() -> closeAll.accept(resources));
        Assertions.assertEquals(expected, count.get(), form);
    }

    /**
     * Every form there is, each handed a resource whose close is interrupted.
     *
     * @return one case per form, closing exactly the resource it is given.
     */
    private static Stream<Arguments> everyFormOfClosing() {
        return Stream.of(
                Arguments.of("closeQuiet",
                        (Consumer<AutoCloseable>) CloseHelper::closeQuiet),
                Arguments.of("closeQuietAll, a collection",
                        (Consumer<AutoCloseable>) resource ->
                                CloseHelper.closeQuietAll(Collections.singletonList(resource))),
                Arguments.of("closeQuietAll, varargs",
                        (Consumer<AutoCloseable>) resource -> CloseHelper.closeQuietAll(resource)));
    }

    /**
     * An interrupt is the one failure which is not the resource's: dropping it leaves the closing thread
     * looking as though it was never interrupted, and whatever waits after the close waits for nothing.
     *
     * @param form    being closed with.
     * @param closeIt the form itself.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyFormOfClosing")
    public void anInterruptedCloseLeavesTheThreadInterrupted(final String form,
                                                             final Consumer<AutoCloseable> closeIt)
            throws Exception {
        final AtomicBoolean stillInterrupted = new AtomicBoolean();
        final AutoCloseable interrupting = () -> {
            throw new InterruptedException("interrupted while closing");
        };

        // on a thread of its own, so the flag cannot outlive this case and reach the next one
        final Thread closing = new Thread(() -> {
            closeIt.accept(interrupting);
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        closing.start();
        closing.join();

        Assertions.assertTrue(stillInterrupted.get(), form + " lost the interrupt");
    }

    @Test
    public void whatIsClosedAfterAnInterruptIsStillClosed() {
        final AtomicInteger closed = new AtomicInteger();
        final AutoCloseable interrupting = () -> {
            throw new InterruptedException("interrupted while closing");
        };

        final Thread closing = new Thread(() -> CloseHelper.closeQuietAll(
                interrupting,
                (AutoCloseable) closed::incrementAndGet));

        Assertions.assertDoesNotThrow(() -> {
            closing.start();
            closing.join();
        });
        Assertions.assertEquals(1, closed.get(), "the close after the interrupted one was skipped");
    }
}
