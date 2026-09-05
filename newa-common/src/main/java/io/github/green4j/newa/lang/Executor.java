/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.lang;

/**
 * Runs work on the thread of whatever handed this out. It is the hop back onto that thread from wherever an
 * answer arrived - a callback of a database driver, a pool of your own.
 */
public interface Executor {

    void execute(Runnable work);

}
