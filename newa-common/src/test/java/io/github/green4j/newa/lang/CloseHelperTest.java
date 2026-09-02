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
