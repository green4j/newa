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

public interface RestEndpointer {
    Endpoint get(String pathExpression, RestHandle handle);

    Endpoint getJson(String pathExpression,
                     JsonRestHandle handle);

    Endpoint getTxt(String pathExpression,
                    TxtRestHandle handle);

    Endpoint post(String pathExpression,
                  RestHandle handle);

    Endpoint postJson(String pathExpression,
                      JsonRestHandle handle);

    Endpoint postTxt(String pathExpression,
                     TxtRestHandle handle);

    Endpoint put(String pathExpression,
                 RestHandle handle);

    Endpoint putJson(String pathExpression,
                     JsonRestHandle handle);

    Endpoint putTxt(String pathExpression,
                    TxtRestHandle handle);

    Endpoint delete(String pathExpression,
                    RestHandle handle);

    Endpoint deleteJson(String pathExpression,
                        JsonRestHandle handle);

    Endpoint deleteTxt(String pathExpression,
                       TxtRestHandle handle);

    Endpoint patch(String pathExpression,
                   RestHandle handle);

    Endpoint patchJson(String pathExpression,
                       JsonRestHandle handle);

    Endpoint patchTxt(String pathExpression,
                      TxtRestHandle handle);
}
