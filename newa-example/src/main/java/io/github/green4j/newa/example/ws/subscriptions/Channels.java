package io.github.green4j.newa.example.ws.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;
import io.github.green4j.newa.websocket.Receiver;
import io.github.green4j.newa.websocket.subscriptions.Channel;
import io.github.green4j.newa.websocket.subscriptions.EntitySubscriptions;

import java.io.Closeable;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Receives commands <b>[CHANNEL]:[ACTION]:[ID]</b>
 * <p><b>CHANNEL</b> - A | B
 * <p><b>ACTION</b> - S (subscribe) | U (unsubscribe)
 *
 */
public class Channels implements Receiver, Closeable {
    private static final String[] ENTITY_IDS = {
            "AA",
            "BB",
            "CC",
            "DD",
            "EE"
    };

    private final ChannelA channelA = new ChannelA();
    private final ChannelB channelB = new ChannelB();

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1);

    public Channels() {
        // initialize channels to know about
        // our test entities on start. The following code creates
        // an instance of EntitySubscriptions for each entity
        Arrays.stream(ENTITY_IDS).forEach(id -> {
            channelA.getOrCreateEntitySubscriptions(id);
            channelB.getOrCreateEntitySubscriptions(id);
        });

        // let's simulate signals to publish
        scheduler.scheduleWithFixedDelay(
                this::publishAnUpdate,
                1,
                1,
                TimeUnit.SECONDS
        );
    }

    @Override
    public void receive(final ClientSession session,
                        final CharSequence message) {
        final String[] command = message.toString().split(":");
        if (command.length != 3) {
            session.send("Error: invalid command format");
            return;
        }

        final String channel = command[0];
        final String action = command[1];
        final String id = command[2];

        final Channel<?> targetChannel = getChannel(channel);
        if (targetChannel == null) {
            session.send("Error: invalid channel :" + channel);
            return;
        }

        final int result = updateSubscription(
                session,
                action,
                targetChannel,
                id
        );
        switch (result) {
            case -1:
                session.send("Error: invalid action :" + action);
                return;
            case 0:
                session.send("Error: unknown entity :" + id);
                return;
        }
    }

    @Override
    public void close() {
        scheduler.shutdown();
        channelA.close();
        channelB.close();
    }

    private static int updateSubscription(final ClientSession session,
                                          final String action,
                                          final Channel<?> targetChannel,
                                          final String id) {
        switch (action) {
            case "S":
                return targetChannel.subscribeForKnownOnly(session, id);
            case "U":
                targetChannel.unsubscribe(session, id);
                return 1;
            default:
                return -1;
        }
    }

    private Channel<?> getChannel(final String channel) {
        switch (channel) {
            case "A":
                return channelA;
            case "B":
                return channelB;
            default:
                return null;
        }
    }

    private void publishAnUpdate() {
        final ThreadLocalRandom rnd = ThreadLocalRandom.current();

        final String entityId = ENTITY_IDS[rnd.nextInt(0, ENTITY_IDS.length)];

        final Channel<?> targetChannel = rnd.nextBoolean() ? channelA : channelB;

        final EntitySubscriptions entitySubscriptions = targetChannel.getEntitySubscriptions(entityId);

        assert entitySubscriptions != null; // since we have applied all the entities in the constructor,
        // we already must have appropriate instance of EntitySubscriptions for any of them.
        // Another option is to call getOrCreateEntitySubscriptions(entityId) to add
        // an instance of EntitySubscriptions on-the-fly

        final int valueToPublish = rnd.nextInt(0, 100);

        entitySubscriptions.forEachSession(
                session -> {
                    session.send(entityId + "=" + valueToPublish);
                }
        );
    }
}
