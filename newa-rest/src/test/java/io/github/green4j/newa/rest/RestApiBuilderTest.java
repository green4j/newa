/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.rest;

import io.github.green4j.newa.rest.handles.JsonHelp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RestApiBuilderTest {

    private static RestApiBuilder builder() {
        return new RestApiBuilder(
                "Test API", "Test description", 1, "0.0.1");
    }

    @Test
    public void testBuilderMetadata() {
        final RestApiBuilder b = builder();
        Assertions.assertEquals("Test API", b.name());
        Assertions.assertEquals("Test description",
                b.description());
        Assertions.assertEquals(1, b.version());
        Assertions.assertEquals("v1", b.fullVersion());
        Assertions.assertEquals("0.0.1", b.buildVersion());
    }

    @Test
    public void testRegisteredEndpoints() {
        final RestApiBuilder b = builder();
        b.getJson("/items", (context, output) ->
                output.stringValue("ok"));
        b.postJson("/items", (context, output) ->
                output.stringValue("created"));
        final Endpoint[] endpoints = b.endpoints();
        Assertions.assertEquals(2, endpoints.length);
    }

    @Test
    public void testMethods() {
        final RestApiBuilder b = builder();
        b.getJson("/a", (context, output) ->
                output.stringValue("a"));
        final Method[] methods = b.methods();
        Assertions.assertEquals(7, methods.length);
        Assertions.assertEquals("GET", methods[0].name());
        Assertions.assertEquals("HEAD", methods[5].name());
        Assertions.assertEquals("OPTIONS", methods[6].name());
    }

    @Test
    public void testBuildWithoutHelp() {
        final RestApiBuilder b = builder();
        b.getJson("/ping", (context, output) ->
                output.stringValue("pong"));
        final RestApi api = b.build();
        Assertions.assertFalse(api.hasHelp());
        Assertions.assertNull(api.helpPath());
    }

    @Test
    public void testBuildWithHelp() {
        final RestApiBuilder b = builder();
        b.getJson("/ping", (context, output) ->
                output.stringValue("pong"));
        final RestApi api =
                b.buildWithHelp(JsonHelp.factory());
        Assertions.assertTrue(api.hasHelp());
        Assertions.assertNotNull(api.helpPath());
    }

    @Test
    public void testPathParameterDescriptionMismatch() {
        final RestApiBuilder b = builder();
        b.getJson("/items/{id}", (context, output) ->
                output.stringValue("item"));
        Assertions.assertThrows(IllegalArgumentException.class,
                b::build);
    }

    @Test
    public void testPathParameterDescriptionMatch() {
        final RestApiBuilder b = builder();
        b.getJson("/items/{id}", (context, output) ->
                output.stringValue("item")
        ).withPathParameterDescriptions("id - Item ID");
        Assertions.assertDoesNotThrow(b::build);
    }
}
