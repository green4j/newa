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
}
