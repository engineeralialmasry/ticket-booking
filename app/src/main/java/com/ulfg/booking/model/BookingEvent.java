package com.ulfg.booking.model;

import java.time.Instant;

public record BookingEvent(
        String bookingId,
        String idempotencyKey,
        State state,
        Instant at,
        String detail
) {
    public static BookingEvent of(String bookingId, String idemKey, State state, String detail) {
        return new BookingEvent(bookingId, idemKey, state, Instant.now(), detail);
    }
}