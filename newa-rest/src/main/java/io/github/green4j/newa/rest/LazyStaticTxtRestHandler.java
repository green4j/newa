/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.newa.text.ByteArrayLineBuilder;
import io.github.green4j.newa.text.LineAppendable;

import java.util.Arrays;

/**
 * A {@code text/plain} response rendered once and served from that buffer ever after, on the terms of
 * {@link LazyStaticJsonRestHandler}.
 */
public abstract class LazyStaticTxtRestHandler extends TextPlainRestHandler {
    private volatile ByteArray content;

    protected LazyStaticTxtRestHandler() {
    }

    @Override
    protected final ByteArray doHandle(final RestContext context) {
        if (content == null) {
            synchronized (this) {
                if (content == null) {
                    final ByteArrayLineBuilder output = lineBuilder();
                    doHandle(output);
                    final ByteArray result = output.array();
                    final byte[] arrayCopy = Arrays.copyOfRange(
                            result.array(),
                            result.start(),
                            result.start() + result.length()
                    );
                    content = new ByteArray() {
                        @Override
                        public byte[] array() {
                            return arrayCopy;
                        }

                        @Override
                        public int start() {
                            return 0;
                        }

                        @Override
                        public int length() {
                            return arrayCopy.length;
                        }
                    };
                }
            }
        }
        return content;
    }

    protected abstract void doHandle(LineAppendable output);
}
