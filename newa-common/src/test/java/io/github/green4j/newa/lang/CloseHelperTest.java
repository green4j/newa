package io.github.green4j.newa.lang;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

class CloseHelperTest {

    @Test
    public void testCloseQuietNull() {
        Assertions.assertDoesNotThrow(
                () -> CloseHelper.closeQuiet(null));
    }

    @Test
    public void testCloseQuietCallsClose() throws Exception {
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
        Assertions.assertDoesNotThrow(
                () -> CloseHelper.closeQuiet(throwing));
    }

    @Test
    public void testCloseQuietAllCollection() {
        final AtomicInteger count = new AtomicInteger();
        final AutoCloseable r1 = count::incrementAndGet;
        final AutoCloseable r2 = count::incrementAndGet;
        CloseHelper.closeQuietAll(Arrays.asList(r1, null, r2));
        Assertions.assertEquals(2, count.get());
    }

    @Test
    public void testCloseQuietAllVarargs() {
        final AtomicInteger count = new AtomicInteger();
        final AutoCloseable r1 = count::incrementAndGet;
        final AutoCloseable r2 = count::incrementAndGet;
        CloseHelper.closeQuietAll(r1, null, r2);
        Assertions.assertEquals(2, count.get());
    }

    @Test
    public void testCloseQuietAllNullCollection() {
        Assertions.assertDoesNotThrow(() ->
                CloseHelper.closeQuietAll(
                        (java.util.Collection<AutoCloseable>) null
                ));
    }

    @Test
    public void testCloseQuietAllNullVarargs() {
        Assertions.assertDoesNotThrow(() ->
                CloseHelper.closeQuietAll(
                        (AutoCloseable[]) null
                ));
    }
}
