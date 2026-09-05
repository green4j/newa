/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

/**
 * Stops the repeating work a {@link Scheduler} started. Idempotent: work already cancelled stays cancelled.
 */
public interface Cancelable {

    void cancel();

}
