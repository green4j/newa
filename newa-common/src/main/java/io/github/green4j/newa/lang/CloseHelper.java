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

import java.util.Collection;

public abstract class CloseHelper {
    public static void closeQuiet(final AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final Exception ignore) {
        }
    }

    public static void closeQuietAll(final Collection<? extends AutoCloseable> resources) {
        if (resources == null) {
            return;
        }
        for (final AutoCloseable r : resources) {
            if (null != r) {
                try {
                    r.close();
                } catch (final Exception ignore) {
                }
            }
        }
    }

    public static void closeQuietAll(final AutoCloseable... resources) {
        if (resources == null) {
            return;
        }
        for (final AutoCloseable r : resources) {
            if (null != r) {
                try {
                    r.close();
                } catch (final Exception ignore) {
                }
            }
        }
    }

    private CloseHelper() {
    }
}
