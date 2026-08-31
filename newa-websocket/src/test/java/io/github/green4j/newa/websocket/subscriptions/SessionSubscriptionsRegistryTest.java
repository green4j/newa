package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a session keeps of its own subscriptions. Re-synchronizing and unsubscribing go through this
 * registry rather than through the channels, so they cost what the session subscribed to - and reach a
 * session which was subscribed without a channel at all.
 */
class SessionSubscriptionsRegistryTest {
    private static final class ValueSubscriptions extends EntitySubscriptions {
        private int value;

        ValueSubscriptions(final String entityId) {
            super(entityId);
        }

        void publishValue(final int newValue) {
            value = newValue;
            publish(session -> session.send("U:" + newValue));
        }

        @Override
        protected void onClientSessionSubscribed(final ClientSession session,
                                                 final long publicationSequence) {
            session.send("S:" + entityId() + ':' + value);
        }
    }

    private static final class TestChannel extends Channel<ValueSubscriptions> {
        @Override
        protected ValueSubscriptions newEntitySubscriptions(final String entityId) {
            return new ValueSubscriptions(entityId);
        }
    }

    private static List<String> framesOf(final ClientSession session) {
        final EmbeddedChannel channel = (EmbeddedChannel) session.channel();

        final List<String> result = new ArrayList<>();
        Object written;
        while ((written = channel.readOutbound()) != null) {
            if (written instanceof TextWebSocketFrame) {
                result.add(((TextWebSocketFrame) written).text());
            }
            ReferenceCountUtil.release(written);
        }
        return result;
    }

    private static ClientSessionSubscriptions subscriptionsOf(final ClientSession session) {
        return ClientSessionSubscriptions.getClientSessionSubscriptions(session);
    }

    private TestSessions sessions;
    private TestChannel channel;

    @BeforeEach
    void setUp() {
        sessions = new TestSessions();
        channel = new TestChannel();
    }

    @AfterEach
    void tearDown() {
        channel.close();
        sessions.closeAll();
    }

    @Test
    void shouldKeepWhatTheSessionIsSubscribedTo() {
        final ClientSession session = sessions.newSession();
        assertEquals(0, subscriptionsOf(session).numberOfSubscribedEntities());

        channel.subscribe(session, "AA");
        channel.subscribe(session, "BB");
        assertEquals(2, subscriptionsOf(session).numberOfSubscribedEntities());

        channel.subscribe(session, "AA"); // subscribed already, nothing is added twice
        assertEquals(2, subscriptionsOf(session).numberOfSubscribedEntities());

        channel.unsubscribe(session, "AA");
        assertEquals(1, subscriptionsOf(session).numberOfSubscribedEntities());
    }

    @Test
    void shouldUnsubscribeASessionSubscribedWithoutAChannel() {
        final ClientSession session = sessions.newSession();

        // straight into the entity, the way an application which owns its own routing would do it
        final ValueSubscriptions entity = new ValueSubscriptions("AA");
        entity.add(session);

        assertEquals(1, entity.numberOfSubscribedSessions());
        assertEquals(1, subscriptionsOf(session).numberOfSubscribedEntities());

        session.close();

        assertEquals(0, entity.numberOfSubscribedSessions(),
                "a session which is gone must be left in no entity at all");
    }

    @Test
    void shouldResynchronizeASessionSubscribedWithoutAChannel() {
        final ClientSession session = sessions.newSession();

        final ValueSubscriptions entity = new ValueSubscriptions("AA");
        entity.add(session);
        assertEquals(List.of("S:AA:0"), framesOf(session));

        entity.publishValue(7);
        assertEquals(List.of("U:7"), framesOf(session));

        subscriptionsOf(session).resync();

        assertEquals(List.of("S:AA:7"), framesOf(session),
                "the snapshot must be re-sent through the registry of the session");
    }

    @Test
    void shouldForgetAnEntityWhichWasClosed() {
        final ClientSession session = sessions.newSession();

        channel.subscribe(session, "AA");
        channel.subscribe(session, "BB");

        channel.removeEntitySubscriptions("AA"); // closes it

        assertEquals(1, subscriptionsOf(session).numberOfSubscribedEntities(),
                "a closed entity is no longer something the session is subscribed to");

        framesOf(session); // the snapshots so far

        subscriptionsOf(session).resync();

        assertEquals(List.of("S:BB:0"), framesOf(session),
                "only what is still subscribed is re-sent");
    }

    @Test
    void shouldResynchronizeNothingAfterUnsubscribing() {
        final ClientSession session = sessions.newSession();

        channel.subscribe(session, "AA");
        channel.unsubscribe(session, "AA");
        framesOf(session);

        subscriptionsOf(session).resync();

        assertEquals(List.of(), framesOf(session));
    }
}
