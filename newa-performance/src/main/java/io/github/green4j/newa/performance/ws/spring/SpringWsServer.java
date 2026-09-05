/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.ws.spring;

import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.ws.WsServer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Properties;

/**
 * Spring Boot on Tomcat, started with its own defaults, in whichever of the two fan-out shapes the run asked
 * for - see {@link SpringWsApplication}.
 * <p>
 * The properties set are the ones a benchmark cannot do without: where to listen, not to log every frame to
 * the console, and the run's own shape, which the configurations read back to know how many channels they
 * publish into and how big a message is. Everything else is left alone. In particular Tomcat's thread pool
 * and its buffers are Boot's, which is the thing being measured rather than a misconfiguration to correct.
 */
public final class SpringWsServer implements WsServer {
    private final ConfigurableApplicationContext context;
    private final SpringWsApplication.Fanout fanout;
    private final int port;

    private SpringWsServer(final ConfigurableApplicationContext context) {
        this.context = context;
        this.fanout = context.getBean(SpringWsApplication.Fanout.class);
        this.port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    /**
     * @param server   {@link SpringWsApplication#RAW} or {@link SpringWsApplication#STOMP}
     * @param port     to listen on, or 0 for an ephemeral one
     * @param workers  delivery threads the raw handler is given, so that it has as many as newa does. The
     *                 STOMP configuration ignores it and keeps the broker's own pool
     * @param channels to publish into
     * @param messageSize bytes a published message is
     * @param rate     each channel publishes at, which the allowance a subscriber is given follows from
     * @return the running server
     */
    public static SpringWsServer start(final String server,
                                       final int port,
                                       final int workers,
                                       final int channels,
                                       final int messageSize,
                                       final long rate) {
        final Properties properties = new Properties();
        properties.setProperty("server.port", Integer.toString(port));
        properties.setProperty("server.address", "127.0.0.1");
        properties.setProperty("spring.main.banner-mode", "off");
        properties.setProperty("logging.level.root", "WARN");
        properties.setProperty(BenchmarkOptions.PREFIX + "server", server);
        properties.setProperty(BenchmarkOptions.PREFIX + "workers", Integer.toString(workers));
        properties.setProperty(BenchmarkOptions.PREFIX + "channels", Integer.toString(channels));
        properties.setProperty(BenchmarkOptions.PREFIX + "message", Integer.toString(messageSize));
        properties.setProperty(BenchmarkOptions.PREFIX + "rate", Long.toString(rate));

        final ConfigurableApplicationContext context =
                new SpringApplicationBuilder(SpringWsApplication.class)
                        .web(WebApplicationType.SERVLET)
                        .properties(properties)
                        .run();
        return new SpringWsServer(context);
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public void publish(final int channel) {
        fanout.publish(channel);
    }

    @Override
    public void close() {
        context.close();
    }
}
