package com.ulfg.booking.core;

import com.ulfg.booking.model.BookingEvent;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class EventLog {
    private final ConcurrentLinkedQueue<BookingEvent> events = new ConcurrentLinkedQueue<>();

    public void append(BookingEvent event) {
        events.add(event);

        System.out.printf(
                "[event] booking=%s key=%s -> %-15s %s%n",
                event.bookingId(),
                event.idempotencyKey(),
                event.state(),
                event.detail()
        );
    }

    public List<BookingEvent> trace(String bookingId) {
        return events.stream()
                .filter(event -> event.bookingId().equals(bookingId))
                .toList();
    }

    public int size() {
        return events.size();
    }
}