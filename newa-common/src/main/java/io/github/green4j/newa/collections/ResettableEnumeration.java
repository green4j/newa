/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

import java.util.Enumeration;

/**
 * An {@link Enumeration} which can be walked again without being built again, so a repeated walk over the
 * same collection allocates once rather than once per pass.
 *
 * @param <T> the type of the elements.
 */
public interface ResettableEnumeration<T> extends Enumeration<T> {

    void reset();

}