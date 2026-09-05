/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.collections;

/**
 * The key of the entry a walk last returned. An enumeration of values implements this so a walk can have
 * both without a map entry being allocated to carry them:
 * <pre>{@code
 * while (elements.hasMoreElements()) {
 *     final V value = elements.nextElement();
 *     final K key = ((KeyEntry<K>) elements).key();
 * }
 * }</pre>
 * Asked before anything has been returned, or after a reset, it throws {@link IllegalStateException}: there
 * is no entry to be about.
 *
 * @param <T> the type of the key.
 */
public interface KeyEntry<T> {

    T key();

}