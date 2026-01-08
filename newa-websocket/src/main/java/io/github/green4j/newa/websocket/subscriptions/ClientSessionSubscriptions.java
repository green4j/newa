package io.github.green4j.newa.websocket.subscriptions;

import io.github.green4j.newa.websocket.ClientSession;

import java.util.ArrayList;
import java.util.List;

public class ClientSessionSubscriptions {
    private final ClientSession session;

    private volatile List<Channel<?>> channelsSubscribedAllTheTime = new ArrayList<>(); // stores
    // all Channels the session was subscribed at least once. Can be accessed by
    // multiple threads

    private volatile Object userData;

    ClientSessionSubscriptions(final ClientSession session) {
        this.session = session;
    }

    public ClientSession session() {
        return session;
    }

    public int numberOfSubscribedChannels() {
        final List<Channel<?>> channels = channelsSubscribedAllTheTime;
        int result = 0;
        for (int i = 0; i < channels.size(); i++) {
            final Channel<?> channel = channels.get(i);
            if (channel.isSubscribed(session)) {
                result++;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserData() {
        return (T) userData;
    }

    public synchronized <T> T putUserData(final T userData) {
        final T old = getUserData();
        this.userData = userData;
        return old;
    }

    public synchronized <T> T putUserDataIfAbsent(final T userData) {
        final T old = getUserData();
        if (old != null) {
            return old;
        }
        this.userData = userData;
        return userData;
    }

    void onSubscribed(final Channel<?> channel) {
        List<Channel<?>> channels = channelsSubscribedAllTheTime;
        if (channels.contains(channel)) {
            return;
        }

        synchronized (this) {
            channels = channelsSubscribedAllTheTime;
            if (channels.contains(channel)) {
                return;
            }

            final List<Channel<?>> newChannels = new ArrayList<>(channels);
            newChannels.add(channel);
            channelsSubscribedAllTheTime = newChannels;
        }
    }

    void unsubscribeAll() {
        final List<Channel<?>> channels = channelsSubscribedAllTheTime;
        for (final Channel<?> channel : channels) {
            channel.unsubscribeAll(session);
        }
        // we are not going to clean up the list of the subscribed Channels
        // to don't make concurrent things complicated. We should be fine
        // with strategy like that, since number of Channels in a server
        // is relatively small, and the list shouldn't grow significantly
    }
}
