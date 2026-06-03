package com.ulfg.booking;

import com.ulfg.booking.core.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyTest {

    @Test
    void sameKeyClaimedExactlyOnce() throws InterruptedException {
        IdempotencyStore store = new IdempotencyStore();
        final String key = "idem-123";
        final int racers = 200;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(racers);
        AtomicInteger owners = new AtomicInteger(); // how many got null (the owner)

        for (int i = 0; i < racers; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (store.claim(key) == null) {   // null == "I own the work"
                        owners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        // CORRECTNESS: out of 200 racing retries, exactly ONE owns the booking.
        assertEquals(1, owners.get(), "exactly one caller should own a given key");
    }
}