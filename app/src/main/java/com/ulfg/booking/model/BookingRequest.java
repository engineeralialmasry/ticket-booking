/*
 * Decompiled with CFR 0.152.
 */
package com.ulfg.booking.model;

public record BookingRequest(String idempotencyKey, String eventId, int quantity) {
}
