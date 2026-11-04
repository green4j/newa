/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.json;

/**
 * A document {@link Utf8JsonReader} has read is not JSON in UTF-8: the message says what the error is and
 * where it was found.
 */
public final class MalformedJsonException extends IllegalArgumentException {
    private static final long serialVersionUID = 6403951263580927101L;

    MalformedJsonException(final String message) {
        super(message);
    }
}
