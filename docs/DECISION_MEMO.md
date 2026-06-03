# Architecture Decision Memo

## Main Decision

The main design decision is to use per-event locking for inventory reservation, combined with idempotency keys and payment calls outside the inventory lock.

This means each event has its own lock. Threads booking the same event must enter the same critical section, but bookings for different events can run independently.

## Q1 — What guarantee does the design provide?

The design guarantees that ticket inventory cannot be oversold.

The critical operation is:

```text
check remaining seats
subtract reserved quantity