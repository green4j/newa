/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest.spring;

import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.rest.RestPayload;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Boot's side of the benchmark, written the way anybody writes one: a controller returning objects,
 * and Jackson turning them into the response.
 * <p>
 * The path variable is named explicitly because this module is not built with {@code -parameters} - it does
 * not use the Spring Boot Gradle plugin, only the starter.
 */
@RestController
public class QuotesController {

    @GetMapping(path = "/v1/quotes/{sequence}", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Quote> quotes(@PathVariable("sequence") final long sequence) {
        final List<Quote> quotes = new ArrayList<>(RestPayload.ROWS);
        for (int row = 0; row < RestPayload.ROWS; row++) {
            quotes.add(Quote.of(RestPayload.key(sequence, row)));
        }
        return quotes;
    }

    @GetMapping(path = "/v1/perf/stats", produces = MediaType.TEXT_PLAIN_VALUE)
    public String stats() {
        return JvmStats.current().render();
    }
}
