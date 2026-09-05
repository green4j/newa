/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.rest.spring;

import io.github.green4j.newa.performance.rest.RestServer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Properties;

/**
 * Spring Boot on Tomcat, started with its own defaults.
 * <p>
 * The properties set are the ones a benchmark cannot do without - where to listen, not to log every request
 * to the console, and the keep-alive cap explained below. In particular the thread pool is left alone: a
 * synchronous, thread-per-request server meeting a thousand clients with the two hundred threads Tomcat
 * gives it is not a misconfiguration to be corrected here, it is the thing being measured. The worker count
 * the benchmark hands the newa server is therefore ignored on this side.
 */
public final class SpringRestServer implements RestServer {
    private final ConfigurableApplicationContext context;
    private final int port;

    private SpringRestServer(final ConfigurableApplicationContext context) {
        this.context = context;
        this.port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    /**
     * @param port to listen on, or 0 for an ephemeral one
     * @return the running server
     */
    public static SpringRestServer start(final int port) {
        final Properties properties = new Properties();
        properties.setProperty("server.port", Integer.toString(port));
        properties.setProperty("server.address", "127.0.0.1");
        properties.setProperty("spring.main.banner-mode", "off");
        properties.setProperty("logging.level.root", "WARN");
        // The one Tomcat default deliberately overridden. Boot's is 100: Tomcat closes a keep-alive
        // connection after its hundredth request, so a throughput run spends a fraction of its time
        // reconnecting rather than answering, and the comparison becomes one of connection setup. It is a
        // real cost of the defaults and it is recorded in the module README, but it is not what this
        // benchmark is measuring, and newa has no equivalent cap to be measured against.
        properties.setProperty("server.tomcat.max-keep-alive-requests", "-1");

        final ConfigurableApplicationContext context =
                new SpringApplicationBuilder(SpringRestApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .properties(properties)
                        .run();
        return new SpringRestServer(context);
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public void close() {
        context.close();
    }
}
