/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

/**
 * Registering a route, once per method and in three forms: {@code xxx} takes a full {@link RestHandle},
 * {@code xxxJson} and {@code xxxTxt} take a handle which renders and returns. What is returned is the
 * {@link Endpoint}, for the descriptions the help endpoint reads.
 * <p>
 * {@link RestApiBuilder} registers under the version prefix and {@link RestApiBuilder#root()} without it,
 * which is the only difference between the two.
 */
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

    Endpoint head(String pathExpression,
                  RestHandle handle);

    Endpoint headJson(String pathExpression,
                      JsonRestHandle handle);

    Endpoint headTxt(String pathExpression,
                     TxtRestHandle handle);

    Endpoint options(String pathExpression,
                     RestHandle handle);

    Endpoint optionsJson(String pathExpression,
                         JsonRestHandle handle);

    Endpoint optionsTxt(String pathExpression,
                        TxtRestHandle handle);
}
