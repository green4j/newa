package io.github.green4j.newa.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CoresTest {

    @Test
    public void clientNeverTakesMoreThanHalfTheMachine() {
        assertTrue(Cores.clientThreads() * 2 <= Cores.available());
    }

    @Test
    public void serverGetsWhatTheClientDidNotTake() {
        assertEquals(Cores.available(), Cores.clientThreads() + Cores.serverThreads());
    }

    @Test
    public void bothHalvesHaveAtLeastOneThread() {
        assertTrue(Cores.clientThreads() >= 1);
        assertTrue(Cores.serverThreads() >= 1);
    }
}
