/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest.spring;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application the Spring server runs. Nothing is configured here on purpose: the point of the comparison
 * is Spring Boot as it comes, thread pool sizes included.
 */
@SpringBootApplication
public class SpringRestApplication {
}
