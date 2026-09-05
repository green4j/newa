/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;

import java.util.Arrays;

/**
 * A JSON response which is rendered the first time it is asked for and served from that buffer ever after -
 * for content which is fixed once the server is built but too costly to render before anybody wants it.
 * The rendering is done once however many threads arrive together.
 */
public abstract class LazyStaticJsonRestHandler extends ApplicationJsonRestHandler {
    private volatile ByteArray content;

    protected LazyStaticJsonRestHandler() {
    }

    protected LazyStaticJsonRestHandler(final Charset responseCharset) {
        super(responseCharset);
    }

    @Override
    protected final ByteArray doHandle(final RestContext context) {
        if (content == null) {
            synchronized (this) {
                if (content == null) {
                    final ByteArrayJsonGenerator generator = jsonGenerator();
                    doHandle(generator.start());
                    final ByteArray result = generator.finish();
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

    protected abstract void doHandle(JsonGenerator output);
}
