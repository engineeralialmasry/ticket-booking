# Architecture Documentation

## 1. Overview

This project is a concurrent ticket booking system for Topic C: Distributed Order / Transaction Processing.

The system is split into two separate Java services:

| Service    | Port | Role                                                      |
| ---------- | ---: | --------------------------------------------------------- |
| BookingApp | 8080 | Accepts booking requests and manages the booking workflow |
| PaymentApp | 8081 | Simulates a downstream payment provider                   |

The services communicate using HTTP. This gives the project a real network boundary, meaning the payment call can experience latency, timeout, or failure.

## 2. High-Level Architecture

```text
Client / LoadTest
      |
      | HTTP POST /bookings
      v
BookingApp :8080
      |
      | Worker pool + bounded queue
      v
BookingPipeline
      |
      |-- IdempotencyStore
      |-- InventoryManager
      |-- EventLog
      |-- Metrics
      |
      | HTTP POST /charge
      v
PaymentApp :8081
```

## 3. Main Components

### BookingApp

BookingApp is the main API service. It exposes:

| Endpoint       | Purpose                                                                  |
| -------------- | ------------------------------------------------------------------------ |
| POST /bookings | Accept a new booking request                                             |
| GET /metrics   | Show completed, failed, rejected, queue depth, throughput, p50, p95, p99 |
| GET /inventory | Show remaining seats for concertA                                        |

BookingApp uses:

* a fixed worker pool
* a bounded queue
* an HTTP server executor
* graceful shutdown hook

### PaymentApp

PaymentApp is a separate service that simulates payment processing.

It supports artificial failure injection using environment variables:

```powershell
$env:PAYMENT_LATENCY_MS="3000"
$env:PAYMENT_FAIL_RATE="0.0"
```

This allows testing payment latency, timeout, retry, and failure recovery.

### BookingPipeline

BookingPipeline coordinates the booking lifecycle:

```text
PLACED
RESERVED
PAYMENT_PENDING
CONFIRMED
FAILED
CANCELLED
```

The pipeline performs these steps:

1. Claim idempotency key.
2. Create booking ID.
3. Log PLACED event.
4. Reserve inventory.
5. Log RESERVED event.
6. Call payment service.
7. Confirm booking if payment succeeds.
8. Release seats and fail booking if payment fails.

### InventoryManager

InventoryManager protects shared ticket inventory.

Shared state:

```text
eventId -> remaining seats
```

Concurrency control:

```text
one lock per event
synchronized(eventLock)
```

This protects the check-then-update operation:

```text
check available seats
subtract reserved quantity
```

This prevents overselling.

Important design decision:

```text
Payment is not called while holding the inventory lock.
```

This prevents slow network I/O from blocking the inventory critical section.

### IdempotencyStore

IdempotencyStore prevents duplicate booking when a request is retried.

Behavior:

| Situation                                           | Result                                 |
| --------------------------------------------------- | -------------------------------------- |
| First request with key K                            | Process normally                       |
| Duplicate request with same key K while in progress | Return in-progress duplicate result    |
| Duplicate request with same key K after completion  | Return prior result, do not book again |

This protects the system from double booking caused by retries.

### EventLog

EventLog stores booking state transitions in a thread-safe queue.

Each event contains:

* bookingId
* idempotencyKey
* state
* timestamp
* detail

Example:

```text
[event] booking=bk-... key=... -> CONFIRMED charged attempt=3
```

This makes the system observable and easier to debug.

### Metrics

Metrics tracks:

* completed bookings
* failed bookings
* rejected bookings
* queue depth
* throughput
* p50 latency
* p95 latency
* p99 latency

The `/metrics` endpoint is used during load testing and failure injection.

### PaymentClient

PaymentClient calls PaymentApp over HTTP.

It supports:

* per-call timeout
* retry attempts
* backoff between retries
* final failed result if all attempts fail

This is important because the payment service is a downstream dependency and can be slow or unavailable.

## 4. Threading Model

The system avoids creating one thread per request.

BookingApp uses:

```text
WORKERS = 32
QUEUE_CAP = 500
```

A bounded worker pool provides controlled concurrency.

Advantages:

* avoids unbounded thread creation
* makes overload visible
* keeps queue depth measurable
* supports graceful shutdown

## 5. Shared State Protection

The main shared state is ticket inventory.

Without synchronization, this can happen:

```text
Thread A reads 1 seat available
Thread B reads 1 seat available
Thread A reserves seat
Thread B reserves same seat
Result: overselling
```

With per-event locking:

```text
Thread A enters event lock
Thread A checks and reserves
Thread A exits event lock
Thread B enters event lock
Thread B sees updated inventory
```

This guarantees no overselling.

## 6. Network Boundary

BookingApp and PaymentApp are separate Java processes:

```text
BookingApp :8080  --->  PaymentApp :8081
```

This matters because real distributed systems must handle:

* latency
* timeout
* failure
* retry
* partial completion
* recovery

The project demonstrates this using artificial payment latency.

## 7. Failure Handling

### Slow payment scenario

PaymentApp was started with:

```powershell
$env:PAYMENT_LATENCY_MS="3000"
```

Then a high-load test was executed.

Observed behavior:

* client errors increased
* failed bookings increased
* p95 and p99 latency increased
* metrics endpoint still responded
* system stayed alive

### Payment failure behavior

If payment fails after retry attempts:

1. Booking is marked FAILED.
2. Reserved seats are released.
3. Idempotency key is cleared.
4. Event log records the failure.
5. Metrics record failed work.

## 8. Load Test Results

| Concurrency | Total Requests |  Throughput |    p50 |    p95 |    p99 |
| ----------: | -------------: | ----------: | -----: | -----: | -----: |
|          50 |           2000 | 187.4 req/s |  224ms |  488ms |  829ms |
|         100 |           5000 | 339.7 req/s |  269ms |  419ms |  913ms |
|         200 |          10000 | 285.2 req/s |  674ms |  958ms | 1753ms |
|         500 |          20000 | 331.0 req/s | 1516ms | 2316ms | 2684ms |
|         800 |          30000 | 496.6 req/s | 1664ms | 1955ms | 3245ms |
|        1000 |          50000 | 822.8 req/s | 1768ms | 2414ms | 2877ms |

The results show that tail latency increases as concurrency increases, which is expected in a loaded concurrent system.

## 9. CPU Benchmark

The project includes a CPU-bound benchmark for ticket hash/signature generation.

| Tickets | Sequential | Parallel | Same Result | Speedup |
| ------: | ---------: | -------: | ----------- | ------: |
|    1000 |       29ms |     21ms | true        |   1.38x |
|    5000 |       96ms |     40ms | true        |   2.40x |
|   10000 |      143ms |     49ms | true        |   2.92x |
|   20000 |      251ms |     85ms | true        |   2.95x |

Sequential execution is the correctness baseline. Parallel execution improves performance for larger inputs.

## 10. Main Tradeoff

The project uses per-event locking instead of a fully lock-free design.

Advantages:

* simple to reason about
* prevents overselling
* easy to prove with tests
* critical section is short

Tradeoff:

* requests for the same event serialize during reservation
* extreme same-event contention can reduce throughput

This tradeoff is acceptable because correctness is more important than maximum throughput in ticket inventory reservation.
