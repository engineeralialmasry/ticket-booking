package com.ulfg.booking.core;

import java.util.concurrent.ConcurrentHashMap;

public final class InventoryManager {
    private final ConcurrentHashMap<String, Integer> seats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public void initEvent(String eventId, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0");
        }

        seats.put(eventId, capacity);
        locks.computeIfAbsent(eventId, key -> new Object());
    }

    public boolean tryReserve(String eventId, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }

        Object eventLock = locks.computeIfAbsent(eventId, key -> new Object());

        synchronized (eventLock) {
            Integer available = seats.get(eventId);

            if (available == null || available < qty) {
                return false;
            }

            seats.put(eventId, available - qty);
            return true;
        }
    }

    public void release(String eventId, int qty) {
        if (qty <= 0) {
            return;
        }

        Object eventLock = locks.computeIfAbsent(eventId, key -> new Object());

        synchronized (eventLock) {
            int available = seats.getOrDefault(eventId, 0);
            seats.put(eventId, available + qty);
        }
    }

    public int remaining(String eventId) {
        return seats.getOrDefault(eventId, 0);
    }
}