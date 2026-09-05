/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

import java.util.Collection;

/**
 * Closing on a path which is already going wrong, where a resource which will not close is not worth a
 * second failure. A null is nothing to close, and a failure to close is dropped rather than thrown.
 *
 * <p>An {@link InterruptedException} is the one which is not dropped: the interrupt is re-asserted on the
 * closing thread, so a close which was interrupted does not leave the thread looking as though it never was.
 */
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
        for (final AutoCloseable resource : resources) {
            closeQuiet(resource);
        }
    }

    public static void closeQuietAll(final AutoCloseable... resources) {
        if (resources == null) {
            return;
        }
        for (int i = 0; i < resources.length; i++) {
            closeQuiet(resources[i]);
        }
    }

    private CloseHelper() {
    }
}
