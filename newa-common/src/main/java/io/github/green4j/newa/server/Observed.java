/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.server;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.function.Consumer;

/**
 * The one way a handler of this package reaches an observer: absent by default, and never able to change
 * what it is being told about. Everything reported here is already decided, so an exception leaving an
 * observer would only surface as a failure of a channel which was being closed on purpose.
 */
final class Observed {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getInstance(Observed.class);

    private Observed() {
    }

    static void by(final ConnectionObserver observer,
                   final Consumer<ConnectionObserver> event) {
        if (observer == null) {
            return;
        }
        try {
            event.accept(observer);
        } catch (final Exception reported) {
            LOGGER.debug("A connection observer failed", reported);
        }
    }

    static void by(final RefusedRequestObserver observer,
                   final Consumer<RefusedRequestObserver> event) {
        if (observer == null) {
            return;
        }
        try {
            event.accept(observer);
        } catch (final Exception reported) {
            LOGGER.debug("A refused request observer failed", reported);
        }
    }
}
