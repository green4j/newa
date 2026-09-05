/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

/**
 * Repeats work on the thread of whatever handed this out - a websocket session hands out the event loop of
 * its channel, so a periodic send lands where the session already is and needs no hop of its own.
 */
public interface Scheduler {

    Cancelable scheduleWithFixedDelay(Runnable work,
                                      long initialDelayMillis,
                                      long delayMillis);

}
