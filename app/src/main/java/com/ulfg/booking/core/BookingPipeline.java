package com.ulfg.booking.core;

import com.ulfg.booking.model.BookingEvent;
import com.ulfg.booking.model.BookingRequest;
import com.ulfg.booking.model.State;
import com.ulfg.booking.payment.PaymentClient;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class BookingPipeline {
    private final InventoryManager inventory;
    private final IdempotencyStore idempotency;
    private final EventLog eventLog;
    private final PaymentClient payment;
    private final Executor workerPool;

    public BookingPipeline(
            InventoryManager inventory,
            IdempotencyStore idempotency,
            EventLog eventLog,
            PaymentClient payment,
            Executor workerPool
    ) {
        this.inventory = inventory;
        this.idempotency = idempotency;
        this.eventLog = eventLog;
        this.payment = payment;
        this.workerPool = workerPool;
    }

    public CompletableFuture<Outcome> submit(BookingRequest req) {
        IdempotencyStore.Entry existing = idempotency.claim(req.idempotencyKey());

        if (existing != null) {
            String detail;

            if (existing.status() == IdempotencyStore.Status.COMPLETED) {
                detail = "duplicate: returning prior result " + existing.result();
                return CompletableFuture.completedFuture(
                        new Outcome(existing.bookingId(), State.CONFIRMED, detail)
                );
            }

            detail = "duplicate: original still in progress";
            return CompletableFuture.completedFuture(
                    new Outcome(existing.bookingId(), State.PAYMENT_PENDING, detail)
            );
        }

        String bookingId = "bk-" + UUID.randomUUID().toString().substring(0, 8);

        log(bookingId, req, State.PLACED, "qty=" + req.quantity() + " event=" + req.eventId());

        boolean reserved = inventory.tryReserve(req.eventId(), req.quantity());

        if (!reserved) {
            log(bookingId, req, State.FAILED, "sold out / not enough seats");
            idempotency.clear(req.idempotencyKey());
            return CompletableFuture.completedFuture(
                    new Outcome(bookingId, State.FAILED, "sold out")
            );
        }

        log(bookingId, req, State.RESERVED, "seats reserved");
        log(bookingId, req, State.PAYMENT_PENDING, "calling payment service");

        return payment.charge(bookingId, req.quantity() * 50)
                .thenApplyAsync(result -> {
                    if (result.success()) {
                        log(bookingId, req, State.CONFIRMED, result.detail());
                        idempotency.complete(req.idempotencyKey(), bookingId, "CONFIRMED");
                        return new Outcome(bookingId, State.CONFIRMED, result.detail());
                    }

                    inventory.release(req.eventId(), req.quantity());
                    log(
                            bookingId,
                            req,
                            State.FAILED,
                            "payment failed, seats released: " + result.detail()
                    );
                    idempotency.clear(req.idempotencyKey());

                    return new Outcome(bookingId, State.FAILED, result.detail());
                }, workerPool);
    }

    private void log(String bookingId, BookingRequest req, State state, String detail) {
        eventLog.append(
                BookingEvent.of(
                        bookingId,
                        req.idempotencyKey(),
                        state,
                        detail
                )
        );
    }

    public record Outcome(String bookingId, State finalState, String detail) {
    }
}