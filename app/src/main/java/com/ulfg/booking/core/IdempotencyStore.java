package com.ulfg.booking.core;

import java.util.concurrent.ConcurrentHashMap;

public final class IdempotencyStore {
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public Entry claim(String idempotencyKey) {
        return store.putIfAbsent(idempotencyKey, Entry.inProgress());
    }

    public void complete(String idempotencyKey, String bookingId, String result) {
        store.put(idempotencyKey, Entry.completed(bookingId, result));
    }

    public void clear(String idempotencyKey) {
        store.remove(idempotencyKey);
    }

    public Entry get(String idempotencyKey) {
        return store.get(idempotencyKey);
    }

    public record Entry(Status status, String bookingId, String result) {
        static Entry inProgress() {
            return new Entry(Status.IN_PROGRESS, null, null);
        }

        static Entry completed(String bookingId, String result) {
            return new Entry(Status.COMPLETED, bookingId, result);
        }
    }

    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }
}