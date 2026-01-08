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
