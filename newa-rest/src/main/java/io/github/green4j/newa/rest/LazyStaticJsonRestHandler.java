/*
 * MIT License
 *
 * Copyright (c) 2023-2026 Anatoly Gudkov and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.jelly.ByteArray;
import io.github.green4j.jelly.JsonGenerator;
import io.github.green4j.newa.json.ByteArrayJsonGenerator;
import io.github.green4j.newa.lang.Charset;

import java.util.Arrays;

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
