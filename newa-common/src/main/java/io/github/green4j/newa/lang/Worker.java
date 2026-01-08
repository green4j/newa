package io.github.green4j.newa.lang;

import io.netty.channel.ChannelFuture;

import java.util.concurrent.atomic.AtomicBoolean;

public class Worker {
    private volatile Stopper stopper;

    public Worker() {
    }

    /**
     * Starts a Work and wait until it is stopped. The stopping can be initiated
     * with a Stopper exposed
     * @param work to do
     * @throws Exception  if a problem happened while doing the work
     */
    public final void doWork(final Work work) throws Exception {
        final AtomicBoolean srvClosed = new AtomicBoolean();

        try {
            try (work) {
                synchronized (this) {
                    if (stopper != null) {
                        throw new IllegalStateException("Started already");
                    }

                    stopper = (cause) -> {
                        if (!srvClosed.compareAndExchange(false, true)) {
                            try {
                                onStoppingProbablyConcurrently(cause);
                            } finally {
                                CloseHelper.closeQuiet(work);
                            }
                        }
                    };
                }

                Runtime.getRuntime().addShutdownHook(
                        new Thread(() ->
                                stopper.stop("Process termination happened"))
                );

                try {
                    final ChannelFuture closeFuture = work.doWork();
                    onStarted();
                    closeFuture.sync();
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            onStopped();
        }
    }

    /**
     * Returns a Stopper to initiate stopping of a Work passed in doWork if any.
     * Must be available after the Work is started. A thread waiting in doWork
     * will be released after stopper().stop(...)
     * @return stopper
     */
    public Stopper stopper() {
        return stopper;
    }

    protected void onStarted() {
    }

    protected void onStoppingProbablyConcurrently(final String cause) {
    }

    protected void onStopped() {
    }
}
