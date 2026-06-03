package com.ulfg.booking;

import com.ulfg.booking.core.InventoryManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryStressTest {

    @Test
    void neverOversells() throws InterruptedException {
        InventoryManager inv = new InventoryManager();
        final int capacity = 100;
        final int attempts = 1000;
        inv.initEvent("concertA", capacity);

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (inv.tryReserve("concertA", 1)) {
                        succeeded.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "tasks did not finish in time");
        pool.shutdown();

        assertEquals(capacity, succeeded.get(), "should reserve exactly capacity");
        assertEquals(0, inv.remaining("concertA"), "no seats left");
        assertTrue(inv.remaining("concertA") >= 0, "never negative (no oversell)");
    }
}