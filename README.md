# Concurrent Ticket Booking System

A concurrent and distributed ticket-booking backend implemented in Java 21.

This project demonstrates concurrency correctness, bounded execution, shared-state protection, idempotent request handling, distributed payment processing, failure recovery, performance measurement, event logging, and graceful shutdown.

## Project Information

| Field | Value |
|---|---|
| Course | Concurrency & Distributed Systems |
| Project Topic | Topic C — Distributed Order / Transaction Processing |
| Application | Concurrent Ticket Booking System |
| Primary Language | Java 21 |
| Build System | Gradle Wrapper |
| Development Environment | Visual Studio Code |
| Main Service | BookingApp |
| Downstream Service | PaymentApp |

## Team Members

| Name | Student ID |
|---|---:|
| Mohamad El Saleh | 6652 |
| Ali Hani | 6668 |
| Ali Al Masri | 6748 |

## Demo Video

A complete demonstration of the system is available at the following link:

[Watch the Concurrent Ticket Booking System Demo](https://drive.google.com/file/d/17UmVkzOImNVj0ksNFuIx39OHQqRVQoV3/view?usp=sharing)

## Table of Contents

1. [Project Overview](#project-overview)
2. [Objectives](#objectives)
3. [System Architecture](#system-architecture)
4. [Core Components](#core-components)
5. [Concurrency and Distributed-System Design](#concurrency-and-distributed-system-design)
6. [Prerequisites](#prerequisites)
7. [Build Instructions](#build-instructions)
8. [Running the Services](#running-the-services)
9. [API Endpoints](#api-endpoints)
10. [Manual Verification](#manual-verification)
11. [Automated Tests](#automated-tests)
12. [Load Testing](#load-testing)
13. [High-Load Stress Testing](#high-load-stress-testing)
14. [Failure Injection](#failure-injection)
15. [CPU Parallelism Benchmark](#cpu-parallelism-benchmark)
16. [Booking Lifecycle and Event Logging](#booking-lifecycle-and-event-logging)
17. [Metrics and Observability](#metrics-and-observability)
18. [Graceful Shutdown](#graceful-shutdown)
19. [Evidence](#evidence)
20. [Conclusion](#conclusion)

## Project Overview

The Concurrent Ticket Booking System is a backend application designed to process many booking requests simultaneously while preserving data correctness.

Unlike a basic CRUD application, the system must coordinate concurrent access to shared ticket inventory, prevent duplicate processing, communicate with an external payment service, handle overload, and remain operational when the downstream payment service becomes slow or unavailable.

The implementation runs as two independent Java processes:

- `BookingApp` receives and processes booking requests.
- `PaymentApp` performs payment operations through a real HTTP network boundary.

## Objectives

The system is designed to demonstrate that it can:

- process concurrent booking requests;
- protect shared ticket inventory;
- prevent ticket overselling;
- prevent duplicate bookings through idempotency keys;
- communicate with a separate payment service over HTTP;
- handle payment latency, timeout, retry, and failure;
- use bounded workers and queue capacity;
- expose intentional overload behavior;
- measure throughput and latency percentiles;
- record booking state transitions;
- verify concurrent correctness through automated tests;
- compare sequential and parallel CPU-bound execution;
- shut down cleanly.

## System Architecture

### Service Overview

| Service | Port | Responsibility |
|---|---:|---|
| `BookingApp` | 8080 | Accepts bookings, protects inventory, executes the booking workflow, and exposes metrics |
| `PaymentApp` | 8081 | Simulates payment processing, latency, and failure |

### Request Flow

```text
Client or LoadTest
        |
        | HTTP POST /bookings
        v
BookingApp :8080
        |
        | Reserve inventory
        | Check idempotency key
        | Record state transition
        |
        | HTTP POST /charge
        v
PaymentApp :8081
        |
        | Payment response
        v
BookingApp
        |
        | Confirm or fail booking
        | Update metrics
        | Record final state
        v
Client
```

The HTTP call from `BookingApp` to `PaymentApp` represents the distributed network boundary. The services do not communicate through direct in-process method calls.

## Core Components

| Component | Responsibility |
|---|---|
| `InventoryManager` | Protects shared ticket inventory and prevents overselling |
| `IdempotencyStore` | Prevents duplicate booking execution when a request is retried |
| `BookingPipeline` | Coordinates reservation, payment, confirmation, cancellation, and failure |
| `PaymentClient` | Calls `PaymentApp` through HTTP and handles timeout or failure behavior |
| `EventLog` | Records booking state transitions and diagnostic details |
| `Metrics` | Tracks completed, failed, rejected, queued, and delayed work |
| `LoadTest` | Generates concurrent HTTP booking requests |
| `CpuBenchmark` | Compares sequential and parallel CPU-bound processing |

## Concurrency and Distributed-System Design

### Shared Inventory Protection

Ticket inventory is shared mutable state. Concurrent requests must not read and update it independently because this could allow multiple clients to reserve the same ticket.

`InventoryManager` protects the inventory update as one correctness-critical operation.

The main invariant is:

```text
remainingInventory >= 0
```

A successful concurrent test must prove that the inventory never becomes negative and that the number of confirmed reservations never exceeds the available ticket count.

### Idempotency

Every booking request uses an idempotency key.

When the same request is retried, the system identifies the previously processed operation instead of creating a second booking or charging the customer twice.

The intended guarantee is:

```text
one idempotency key -> one logical booking result
```

### Bounded Execution

`BookingApp` uses a bounded worker configuration:

```text
workers=32
queueCap=500
```

This prevents unlimited thread creation and unlimited queue growth.

When the system becomes saturated, excess work can be rejected explicitly instead of consuming memory indefinitely.

### Payment Boundary

Payment processing occurs in a separate Java process through HTTP:

```text
BookingApp :8080 -> PaymentApp :8081
```

This boundary introduces realistic distributed-system behavior:

- network latency;
- timeout risk;
- service unavailability;
- partial failure;
- retry behavior;
- duplicate-request risk;
- failure propagation.

### Failure Handling

The booking workflow distinguishes between business state and downstream payment state.

A booking may be:

- placed;
- reserved;
- waiting for payment;
- confirmed;
- failed;
- cancelled.

Slow or failed payment requests do not terminate the entire booking service. They are handled through bounded waiting, retry logic, failure recording, and inventory recovery where required.

## Prerequisites

Install the following software before building the project:

- Windows 10 or Windows 11;
- PowerShell;
- Java Development Kit 21 or newer;
- Git;
- Visual Studio Code;
- VS Code Extension Pack for Java.

Verify Java:

```powershell
java -version
javac -version
```

The reported Java version must be 21 or newer.

Gradle does not need to be installed globally because the repository includes the Gradle Wrapper.

## Build Instructions

Open PowerShell in the project root:

```powershell
cd C:\Projects\ticket-booking
.\gradlew.bat clean build --no-daemon
```

Expected result:

```text
BUILD SUCCESSFUL
```

### Windows Gradle Lock Workaround

If Gradle reports a lock-listener issue on Windows, run:

```powershell
$env:GRADLE_OPTS="-Dorg.gradle.cache.internal.locklistener=false"
.\gradlew.bat clean build --no-daemon
```

Build evidence:

```text
docs/evidence/final_clean_build_after_recovery.png
```

## Running the Services

The payment service must be running before booking requests are submitted.

### Terminal A — Start PaymentApp

```powershell
cd C:\Projects\ticket-booking
java -cp "app\build\classes\java\main" com.ulfg.booking.PaymentApp
```

Expected output:

```text
[payment] listening on :8081
```

### Terminal B — Start BookingApp

```powershell
cd C:\Projects\ticket-booking
java -cp "app\build\classes\java\main" com.ulfg.booking.BookingApp
```

Expected output:

```text
[booking] listening on :8080  workers=32 queueCap=500
```

### Terminal C — Submit Requests and Inspect State

Use a third PowerShell terminal for health checks, booking requests, metrics, inventory inspection, and load tests.

## API Endpoints

### BookingApp

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/bookings` | Submits a booking request |
| `GET` | `/metrics` | Returns booking and execution metrics |
| `GET` | `/inventory` | Returns the current inventory state |

### PaymentApp

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/health` | Confirms that the payment service is available |
| `POST` | `/charge` | Processes a payment request |

## Manual Verification

Run the following commands while both services are active:

```powershell
Invoke-RestMethod -Uri http://localhost:8081/health

Invoke-RestMethod `
    -Method Post `
    -Uri http://localhost:8080/bookings

Invoke-RestMethod -Uri http://localhost:8080/metrics

Invoke-RestMethod -Uri http://localhost:8080/inventory
```

Expected behavior:

```text
Payment service status is UP
Booking request is ACCEPTED
Completed booking count increases
Available inventory decreases
```

Manual-test evidence:

```text
docs/evidence/successful_booking_demo.png
```

## Automated Tests

Run the complete test suite:

```powershell
cd C:\Projects\ticket-booking
.\gradlew.bat test
```

Verified tests:

| Test | Purpose | Result |
|---|---|---|
| `InventoryStressTest` | Verifies inventory correctness under concurrent access | Passed |
| `IdempotencyTest` | Verifies duplicate requests do not create duplicate bookings | Passed |

Test summary:

```text
2 tests completed
0 failures
100% successful
```

Test evidence:

```text
docs/evidence/final_clean_build_after_recovery.png
```

## Load Testing

Start both `BookingApp` and `PaymentApp` before executing the load tests.

### Test Commands

```powershell
java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 50 2000

java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 100 5000

java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 200 10000
```

The first argument is the number of concurrent clients. The second argument is the total number of requests.

### Measured Results

| Concurrent Clients | Total Requests | Throughput | p50 | p95 | p99 | HTTP 429 |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 2,000 | 407.2 req/s | 104 ms | 213 ms | 474 ms | 0 |
| 100 | 5,000 | 510.7 req/s | 175 ms | 265 ms | 684 ms | 0 |
| 200 | 10,000 | 518.7 req/s | 344 ms | 460 ms | 1,333 ms | 0 |

Evidence:

```text
docs/evidence/load_tests_50_100_200_clients.png
```

### Interpretation

Throughput increased as more concurrent clients were introduced because the booking service could overlap waiting time, particularly the HTTP payment operation.

However, latency also increased with concurrency. The difference between p50 and p99 shows that queueing and scheduling pressure primarily affected the slowest requests.

The results demonstrate that throughput and latency must be evaluated together. A system can complete more requests per second while individual clients experience greater tail latency.

## High-Load Stress Testing

The system was also tested with substantially larger client populations.

### Measured Results

| Concurrent Clients | Total Requests | Throughput | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|---:|
| 500 | 20,000 | 579.9 req/s | 987 ms | 1,548 ms | 1,978 ms |
| 800 | 30,000 | 500.1 req/s | 1,558 ms | 1,779 ms | 2,318 ms |
| 1,000 | 50,000 | 828.4 req/s | 1,939 ms | 2,519 ms | 2,927 ms |

Evidence:

```text
docs/evidence/high_load_stress_results.png
```

### Interpretation

Under heavy load:

- requests spent more time waiting for workers;
- queueing delay increased;
- median and tail latency increased;
- throughput did not scale linearly with concurrency;
- the service remained operational.

The measurements show that adding clients does not automatically improve performance. Worker capacity, queue capacity, payment latency, context switching, and shared-resource contention determine the saturation point.

Performance results are specific to the machine, operating system, JVM state, and active background workloads used during the experiment.

## Failure Injection

The payment service supports controlled latency and failure configuration through environment variables.

### Slow Payment Scenario

Restart `PaymentApp` with three seconds of artificial latency:

```powershell
$env:PAYMENT_LATENCY_MS="3000"
$env:PAYMENT_FAIL_RATE="0.0"

java -cp "app\build\classes\java\main" com.ulfg.booking.PaymentApp
```

Then execute the stress test:

```powershell
java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 1000 50000
```

### Observed Behavior

During the slow-payment experiment:

- payment-related errors increased;
- failed booking count increased;
- request latency increased;
- workers remained occupied for longer periods;
- the metrics endpoint continued responding;
- `BookingApp` remained alive;
- the failure was visible through metrics and event logs.

Evidence:

```text
docs/evidence/slow_payment_errors_latency_metrics_result.png
```

### Failure-Handling Conclusion

The experiment demonstrates partial failure.

`PaymentApp` became slow, but `BookingApp` did not crash. The booking service contained the downstream failure, exposed degraded behavior through metrics, and continued serving operational endpoints.

## CPU Parallelism Benchmark

The project includes a separate CPU-bound benchmark to compare sequential and parallel processing.

Run:

```powershell
java -cp "app\build\classes\java\main" com.ulfg.booking.CpuBenchmark
```

### Measured Results

| Tickets | Sequential | Parallel | Same Result | Speedup |
|---:|---:|---:|:---:|---:|
| 1,000 | 19 ms | 9 ms | true | 2.11x |
| 5,000 | 69 ms | 15 ms | true | 4.60x |
| 10,000 | 114 ms | 31 ms | true | 3.68x |
| 20,000 | 231 ms | 56 ms | true | 4.13x |

Evidence:

```text
docs/evidence/cpu_benchmark_result.png
```

### Interpretation

The benchmark verifies correctness before evaluating speed:

```text
sequential result == parallel result
```

Parallel execution produced measurable speedup for the tested workloads. The amount of speedup varied because parallel execution introduces splitting, scheduling, synchronization, and result-combination overhead.

The benchmark results are machine-specific and depend on:

- processor core count;
- JVM warm-up;
- background activity;
- workload size;
- task granularity;
- scheduling overhead.

Parallel execution is therefore measured rather than assumed to be faster.

## Booking Lifecycle and Event Logging

The booking workflow uses the following states:

```text
PLACED
   |
   v
RESERVED
   |
   v
PAYMENT_PENDING
   |
   +--------------------+
   |                    |
   v                    v
CONFIRMED             FAILED
                        |
                        v
                    CANCELLED
```

Depending on the failure location, a booking may move to `FAILED` or `CANCELLED`.

### Event Record

Each event contains:

- `bookingId`;
- `idempotencyKey`;
- `state`;
- `detail`.

Example:

```text
[event] booking=bk-... key=... -> CONFIRMED charged attempt=3
```

The event log provides a trace of the booking workflow and helps answer:

- which booking changed state;
- which idempotency key was used;
- whether payment was attempted;
- whether the booking succeeded or failed;
- how many attempts were required;
- where a failure occurred.

## Metrics and Observability

The system tracks operational and performance information including:

- completed bookings;
- failed bookings;
- rejected requests;
- active or queued work;
- queue depth;
- throughput;
- request latency;
- p50 latency;
- p95 latency;
- p99 latency.

Metrics can be inspected using:

```powershell
Invoke-RestMethod -Uri http://localhost:8080/metrics
```

Inventory can be inspected using:

```powershell
Invoke-RestMethod -Uri http://localhost:8080/inventory
```

These endpoints make it possible to compare system behavior before, during, and after load or failure experiments.

## Graceful Shutdown

Both services are designed to shut down cleanly.

Stop each Java process using:

```text
Ctrl+C
```

The graceful-shutdown path allows the application to stop accepting new work, terminate its execution resources, and release service resources instead of leaving unnecessary background threads running.

## Evidence

All screenshots and measured outputs are stored under:

```text
docs/evidence/
```

| Evidence File | Description |
|---|---|
| `final_clean_build_after_recovery.png` | Successful clean Gradle build and automated-test evidence |
| `successful_booking_demo.png` | Manual booking, inventory, and metrics verification |
| `load_tests_50_100_200_clients.png` | Standard concurrent load-test results |
| `high_load_stress_results.png` | High-load stress-test measurements |
| `slow_payment_errors_latency_metrics_result.png` | Slow downstream-payment failure experiment |
| `cpu_benchmark_result.png` | Sequential and parallel CPU benchmark |

## Correctness and Engineering Guarantees

| Requirement | Mechanism | Evidence |
|---|---|---|
| Thread-safe inventory | Protected inventory update | `InventoryStressTest` |
| No overselling | Atomic reservation logic | Inventory remains valid under stress |
| Duplicate protection | Idempotency key store | `IdempotencyTest` |
| Bounded resources | 32 workers and queue capacity of 500 | Booking service startup configuration |
| Distributed communication | HTTP call to separate `PaymentApp` process | Ports 8080 and 8081 |
| Failure containment | Timeout, retry, failure state, and metrics | Slow-payment experiment |
| Measurable behavior | Throughput and p50/p95/p99 latency | Load-test tables |
| Traceability | Booking state-transition event log | Event output |
| CPU parallelism | Sequential and parallel comparison | `CpuBenchmark` |
| Clean termination | Graceful shutdown behavior | Controlled service stop |

## Conclusion

The Concurrent Ticket Booking System demonstrates the main concurrency and distributed-system challenges of a real transaction-processing application.

The project includes:

- concurrent HTTP request processing;
- protected shared ticket inventory;
- overselling prevention;
- idempotent retry handling;
- bounded workers and queue capacity;
- a real HTTP payment-service boundary;
- timeout and downstream-failure behavior;
- booking state-transition logging;
- throughput and latency-percentile measurement;
- high-load stress testing;
- automated concurrency-correctness tests;
- sequential and parallel CPU benchmarking;
- graceful shutdown.

The final result is not only a functional booking API. It is a measured concurrent system with explicit correctness guarantees, bounded resource usage, distributed failure behavior, and supporting evidence.
