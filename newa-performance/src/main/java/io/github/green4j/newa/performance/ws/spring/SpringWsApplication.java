/*
 * Copyright (c) 2023-2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.newa.performance.ws.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.green4j.newa.performance.BenchmarkOptions;
import io.github.green4j.newa.performance.JvmStats;
import io.github.green4j.newa.performance.ws.WsEvent;
import io.github.green4j.newa.performance.ws.WsPayload;
import io.github.green4j.newa.performance.ws.WsServer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring's two sides of the fan-out benchmark, and the one endpoint they share with newa's.
 * <p>
 * Which of the two is configured is decided by {@code newa.perf.server}, so both are described here once and
 * a run picks one. They are two genuinely different answers to the same question:
 * <ul>
 * <li>{@link RawConfig} is {@code @EnableWebSocket} with a handler of one's own - the shape closest to
 * newa's, where the application keeps the subscribers and does the fan-out itself.</li>
 * <li>{@link StompConfig} is {@code @EnableWebSocketMessageBroker} with the simple broker - the shape Spring
 * calls a subscription, where the application publishes to a destination and the framework decides who
 * gets it.</li>
 * </ul>
 */
@SpringBootApplication
public class SpringWsApplication {
    /**
     * The value of {@code newa.perf.server} which selects {@link RawConfig}.
     */
    public static final String RAW = "spring";

    /**
     * The value of {@code newa.perf.server} which selects {@link StompConfig}.
     */
    public static final String STOMP = "spring-stomp";

    /**
     * What either configuration offers the benchmark: publish one message into one channel, now, on the
     * calling thread. The thread is the benchmark's - one per channel, keeping the offered rate - so that
     * what differs between the servers is the delivery and not the generation.
     */
    public interface Fanout {
        /**
         * @param channel index to publish into
         */
        void publish(int channel);
    }

    /**
     * The same statistics endpoint newa's server answers, so that {@code ServerProcess} probes one URL
     * whatever it forked and the two costs are read the same way.
     */
    @RestController
    public static class StatsController {
        @GetMapping(path = JvmStats.PATH, produces = MediaType.TEXT_PLAIN_VALUE)
        public String stats() {
            return JvmStats.current().render();
        }
    }

    /**
     * The events of every channel. Shared by both configurations, so the two Spring servers and the newa one
     * publish byte for byte the same thing - a benchmark whose servers send different messages measures
     * nothing.
     * <p>
     * An event is an object here and becomes JSON through Jackson, because that is how a Spring application
     * is written. Both the object and the string are built once per publication rather than once per
     * subscriber, which is the least the shape allows, and both are counted.
     */
    static final class Messages {
        private final ObjectMapper mapper = new ObjectMapper();
        private final String pad;

        Messages(final int messageSize) {
            this.pad = WsPayload.padding(messageSize);
        }

        /**
         * @param channel to render for
         * @param sequence of this publication
         * @return the event, for the broker to convert as it sees fit
         */
        WsEvent event(final int channel,
                      final long sequence) {
            return WsEvent.of(channel, sequence, System.nanoTime(), pad);
        }

        /**
         * @param channel to render for
         * @param sequence of this publication
         * @return the message, as the raw handler has to send it
         */
        String render(final int channel,
                      final long sequence) {
            try {
                return mapper.writeValueAsString(event(channel, sequence));
            } catch (final JsonProcessingException e) {
                throw new IllegalStateException("Jackson could not serialise an event", e);
            }
        }
    }

    /**
     * {@code @EnableWebSocket} with a handler which keeps its own subscribers: no broker, no destinations,
     * the application doing the fan-out. This is the Spring shape which lines up with newa's channel.
     */
    @Configuration
    @EnableWebSocket
    @ConditionalOnProperty(name = "newa.perf.server", havingValue = RAW)
    public static class RawConfig implements WebSocketConfigurer {
        private final FanoutHandler handler;

        public RawConfig(@Value("${newa.perf.channels}") final int channels,
                         @Value("${newa.perf.message}") final int messageSize,
                         @Value("${newa.perf.workers}") final int workers,
                         @Value("${newa.perf.rate}") final long rate) {
            this.handler = new FanoutHandler(channels, messageSize, workers, rate);
        }

        @Bean
        public Fanout fanout() {
            return handler;
        }

        @Override
        public void registerWebSocketHandlers(final WebSocketHandlerRegistry registry) {
            registry.addHandler(handler, WsPayload.PATH).setAllowedOriginPatterns("*");
        }
    }

    /**
     * The handler {@link RawConfig} registers.
     * <p>
     * <b>Delivery.</b> A session is pinned to one of {@code workers} single threaded executors and is only
     * ever written by that one, which is what keeps its messages in the order they were published - a shared
     * pool would deliver two publications of one channel to one subscriber in whichever order it happened to
     * run them, and no subscriber could then tell a reordering from a hole. It is also the same number of
     * delivery threads the newa server is given, so the two are compared doing the same amount of work on
     * the same amount of machine.
     * <p>
     * <b>A subscriber which cannot keep up</b> is closed once it is further behind than the run allows,
     * which is the answer newa's non-skipping mode gives too. The allowance is counted per session: the
     * executor's own queue cannot be the limit, because one queue serves the thirty-odd sessions pinned to
     * that thread. So the queue is unbounded and each session counts what is outstanding for it. Sessions are
     * wrapped in {@link ConcurrentWebSocketSessionDecorator} as Spring prescribes for a broadcast; with one
     * sender per session its buffer is never contended.
     */
    static final class FanoutHandler extends TextWebSocketHandler implements Fanout, DisposableBean {
        private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

        private final Messages messages;
        private final long[] sequences;
        private final List<List<WebSocketSession>> subscribers = new ArrayList<>();
        private final ThreadPoolExecutor[] senders;
        private final AtomicInteger pinned = new AtomicInteger();

        /**
         * What one subscriber may be behind by: bytes in the session's buffer, the same in messages queued
         * for its sender thread.
         */
        private final int budgetBytes;
        private final int budgetMessages;

        FanoutHandler(final int channels,
                      final int messageSize,
                      final int workers,
                      final long rate) {
            this.budgetBytes = WsServer.outboundBudgetBytes(channels, rate, messageSize);
            this.messages = new Messages(messageSize);
            this.sequences = new long[channels];
            for (int i = 0; i < channels; i++) {
                subscribers.add(new CopyOnWriteArrayList<>());
            }
            // the same allowance in bytes as the other two servers, expressed in the messages a session may
            // have outstanding - a queue counted in entries would be a different limit at every message size
            this.budgetMessages = Math.max(1, budgetBytes / messageSize);
            this.senders = new ThreadPoolExecutor[workers];
            for (int i = 0; i < workers; i++) {
                senders[i] = new ThreadPoolExecutor(
                        1, 1, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        new SenderThreads(i));
            }
        }

        @Override
        public void publish(final int channel) {
            final String message = messages.render(channel, ++sequences[channel]);
            final TextMessage frame = new TextMessage(message); // rendered once, sent to everybody

            final List<WebSocketSession> sessions = subscribers.get(channel);
            for (int i = 0; i < sessions.size(); i++) {
                final WebSocketSession session = sessions.get(i);
                final Pinned target = (Pinned) session.getAttributes().get(Pinned.KEY);
                if (target.outstanding.get() >= budgetMessages) {
                    close(session); // as far behind as this run allows anybody to be
                    continue;
                }
                target.outstanding.incrementAndGet();
                target.sender.execute(() -> {
                    target.outstanding.decrementAndGet();
                    send(session, frame);
                });
            }
        }

        private void send(final WebSocketSession session,
                          final TextMessage frame) {
            try {
                session.sendMessage(frame);
            } catch (final IOException | IllegalStateException e) {
                close(session);
            }
        }

        private static void close(final WebSocketSession session) {
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (final IOException ignored) {
                // it is going away either way
            }
        }

        @Override
        public void afterConnectionEstablished(final WebSocketSession session) {
            final ThreadPoolExecutor sender = senders[pinned.getAndIncrement() % senders.length];
            session.getAttributes().put(Pinned.KEY, new Pinned(
                    new ConcurrentWebSocketSessionDecorator(
                            session, SEND_TIME_LIMIT_MILLIS, budgetBytes),
                    sender));
        }

        @Override
        protected void handleTextMessage(final WebSocketSession session,
                                         final TextMessage message) {
            final String command = message.getPayload();
            if (!command.startsWith(WsPayload.SUBSCRIBE)) {
                return;
            }
            final int channel = channelOf(command.substring(WsPayload.SUBSCRIBE.length()));
            if (channel < 0 || channel >= subscribers.size()) {
                return;
            }
            final Pinned pin = (Pinned) session.getAttributes().get(Pinned.KEY);
            subscribers.get(channel).add(pin.session);
        }

        @Override
        public void afterConnectionClosed(final WebSocketSession session,
                                          final CloseStatus status) {
            final Pinned pin = (Pinned) session.getAttributes().get(Pinned.KEY);
            if (pin == null) {
                return;
            }
            for (int i = 0; i < subscribers.size(); i++) {
                subscribers.get(i).remove(pin.session);
            }
        }

        @Override
        public void destroy() {
            for (int i = 0; i < senders.length; i++) {
                senders[i].shutdownNow();
            }
        }

        private static int channelOf(final String entityId) {
            try {
                return Integer.parseInt(entityId.substring(1));
            } catch (final RuntimeException e) {
                return -1;
            }
        }

        /**
         * What a session carries: the decorated session everything is written through, and the one thread
         * which is allowed to write it.
         */
        private static final class Pinned {
            private static final String KEY = "newa.perf.pinned";

            private final WebSocketSession session;
            private final ThreadPoolExecutor sender;

            /**
             * Messages submitted for this session and not yet written. This is the count the allowance is
             * enforced against, and it is per session rather than per thread.
             */
            private final AtomicInteger outstanding = new AtomicInteger();

            private Pinned(final WebSocketSession session,
                           final ThreadPoolExecutor sender) {
                this.session = session;
                this.sender = sender;
            }
        }

        private static final class SenderThreads implements java.util.concurrent.ThreadFactory {
            private final int index;

            private SenderThreads(final int index) {
                this.index = index;
            }

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable, "spring-perf-sender-" + index);
                thread.setDaemon(true);
                return thread;
            }
        }
    }

    /**
     * {@code @EnableWebSocketMessageBroker} with the simple broker - what Spring means by a subscription.
     * The application publishes to a destination and never sees a subscriber; who gets it, on which thread,
     * and how much of it may be in flight are the framework's to decide, and that is what is being measured
     * against the shape above.
     */
    @Configuration
    @EnableWebSocketMessageBroker
    @ConditionalOnProperty(name = "newa.perf.server", havingValue = STOMP)
    public static class StompConfig implements WebSocketMessageBrokerConfigurer {
        private final Messages messages;
        private final long[] sequences;

        private final int budgetBytes;

        public StompConfig(@Value("${newa.perf.channels}") final int channels,
                           @Value("${newa.perf.message}") final int messageSize,
                           @Value("${newa.perf.rate}") final long rate) {
            this.messages = new Messages(messageSize);
            this.sequences = new long[channels];
            this.budgetBytes = WsServer.outboundBudgetBytes(channels, rate, messageSize);
        }

        @Bean
        public Fanout fanout(final SimpMessagingTemplate template) {
            return channel -> template.convertAndSend(
                    WsPayload.TOPIC + WsPayload.channelId(channel),
                    messages.event(channel, ++sequences[channel]));
            // the event goes over as an object, and the broker's own Jackson converter turns it into the
            // JSON which reaches the wire - which is the point of measuring this shape at all: here the
            // serialisation is the framework's business rather than the application's
        }

        /**
         * Pins the broker's outbound channel to one thread, which is what an ordered subscription costs here.
         * <p>
         * Boot gives {@code clientOutboundChannel} {@code availableProcessors() * 2} threads, so two
         * publications to one destination are two tasks which may run in either order and reach a subscriber
         * in the order they won - {@code StompDeliveryOrderTest} measures it. The other two servers deliver an
         * ordered stream, so this one is configured to as well, and one thread is the only way: the executor
         * is handed a {@code Runnable} per handler and cannot pin a session's tasks to a thread the way
         * {@link FanoutHandler} does. The throughput it costs is reported rather than avoided.
         * <p>
         * {@code -Poutbound=0} puts Boot's pool back, which is the experiment that shows the reordering.
         *
         * @param registration of the channel
         */
        @Override
        public void configureClientOutboundChannel(final ChannelRegistration registration) {
            final int threads = Integer.parseInt(BenchmarkOptions.property("outbound", "1"));
            if (threads > 0) {
                registration.taskExecutor().corePoolSize(threads).maxPoolSize(threads);
            }
        }

        /**
         * The broker's allowance for a subscriber which has fallen behind, the same
         * {@link WsServer#outboundBudgetBytes(int, long, int)} the other two are held to rather than Spring's
         * 512 KB, which at these rates is storage rather than slack.
         *
         * @param registration of the transport
         */
        @Override
        public void configureWebSocketTransport(final WebSocketTransportRegistration registration) {
            registration.setSendBufferSizeLimit(budgetBytes);
        }

        @Override
        public void registerStompEndpoints(final StompEndpointRegistry registry) {
            registry.addEndpoint(WsPayload.PATH).setAllowedOriginPatterns("*"); // no SockJS: the
            // benchmark's client speaks WebSocket, and a fallback transport would measure the fallback
        }

        @Override
        public void configureMessageBroker(final MessageBrokerRegistry registry) {
            registry.enableSimpleBroker(WsPayload.TOPIC.substring(0, WsPayload.TOPIC.length() - 1));
        }
    }
}
