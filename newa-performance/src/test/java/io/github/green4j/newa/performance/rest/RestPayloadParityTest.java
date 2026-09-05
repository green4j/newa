/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest;

import io.github.green4j.newa.performance.JvmStats;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one test which keeps the comparison honest: whatever either side is changed to, both servers have to
 * answer the same bytes. A benchmark whose two servers answer differently measures nothing.
 */
public class RestPayloadParityTest {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    public void bothServersAnswerTheSameDocument() throws Exception {
        try (RestServer newa = RestServerMain.start(RestServerMain.NEWA, 0, 1);
                RestServer spring = RestServerMain.start(RestServerMain.SPRING, 0, 1)) {

            for (final long sequence : new long[]{0, 1, 42, 1023}) {
                final HttpResponse<String> fromNewa = get(newa.port(), RestPayload.PATH_PREFIX + sequence);
                final HttpResponse<String> fromSpring =
                        get(spring.port(), RestPayload.PATH_PREFIX + sequence);

                assertEquals(200, fromNewa.statusCode());
                assertEquals(200, fromSpring.statusCode());
                assertEquals(fromNewa.body(), fromSpring.body(),
                        "The two servers answered differently for sequence " + sequence);
                assertTrue(contentType(fromNewa).startsWith("application/json"), contentType(fromNewa));
                assertTrue(contentType(fromSpring).startsWith("application/json"), contentType(fromSpring));
            }
        }
    }

    @Test
    public void theDocumentDependsOnWhatWasAskedFor() throws Exception {
        try (RestServer newa = RestServerMain.start(RestServerMain.NEWA, 0, 1)) {
            final String first = get(newa.port(), RestPayload.PATH_PREFIX + 1).body();
            final String second = get(newa.port(), RestPayload.PATH_PREFIX + 2).body();
            assertTrue(first.length() > 1000, "The response should be about half a screen, was "
                    + first.length() + " bytes");
            assertTrue(!first.equals(second), "The response must depend on the sequence asked for");
        }
    }

    @Test
    public void bothServersReportTheirOwnStatistics() throws Exception {
        try (RestServer newa = RestServerMain.start(RestServerMain.NEWA, 0, 1);
                RestServer spring = RestServerMain.start(RestServerMain.SPRING, 0, 1)) {

            assertTrue(get(newa.port(), JvmStats.PATH).body().contains("gcCount="));
            assertTrue(get(spring.port(), JvmStats.PATH).body().contains("gcCount="));
        }
    }

    private HttpResponse<String> get(final int port,
                                     final String path) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String contentType(final HttpResponse<String> response) {
        return response.headers().firstValue("content-type").orElse("");
    }
}
