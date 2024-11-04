package io.github.green4j.newa.lang;

public interface Scheduler {

    Cancelable scheduleWithFixedDelay(Runnable work, long initialDelayMillis, long delayMillis);

}
