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
