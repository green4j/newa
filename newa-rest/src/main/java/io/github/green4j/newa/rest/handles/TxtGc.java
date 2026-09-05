/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest.handles;

/**
 * Asks for a garbage collection and answers {@code OK}. An operator's endpoint, not a public one.
 */
public class TxtGc extends TxtExecute {
    public TxtGc() {
        super(System::gc);
    }
}