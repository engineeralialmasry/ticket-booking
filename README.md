# Concurrent Ticket Booking System

Course: Concurrency & Distributed Systems
Project Topic: Topic C — Distributed Order / Transaction Processing
Application: Concurrent Ticket Booking System
Language: Java 21
Build Tool: Gradle Wrapper
IDE: Visual Studio Code

## 1. Project Goal

This project implements a concurrent ticket booking backend where many clients can book tickets at the same time.

The goal is to prove that the system can:

* handle concurrent booking requests
* protect shared ticket inventory
* prevent overselling
* prevent duplicate bookings using idempotency keys
* call a separate payment service over HTTP
* survive payment latency and failure
* measure throughput, p50, p95, and p99 latency
* log booking state transitions
* shut down cleanly

This is not a simple CRUD application. The project focuses on concurrency correctness, bounded execution, failure handling, and measurable behavior under load.

## 2. Main Services

| Service    | Port | Purpose                  |
| ---------- | ---: | ------------------------ |
| BookingApp | 8080 | Main booking service     |
| PaymentApp | 8081 | Separate payment service |

## 3. Main Components

| Component        | Purpose                                                                  |
| ---------------- | ------------------------------------------------------------------------ |
| InventoryManager | Protects shared ticket inventory                                         |
| IdempotencyStore | Prevents duplicate bookings when requests are retried                    |
| BookingPipeline  | Runs the booking workflow                                                |
| PaymentClient    | Calls the PaymentApp over HTTP                                           |
| EventLog         | Logs booking state transitions                                           |
| Metrics          | Tracks completed, failed, rejected, queue depth, throughput, and latency |
| LoadTest         | Sends concurrent booking requests                                        |
| CpuBenchmark     | Compares sequential vs parallel CPU-bound work                           |

## 4. Architecture Summary

The system uses two separate Java processes:

```
Client / LoadTest
      |
      | HTTP POST /bookings
      v
BookingApp :8080
      |
      | HTTP POST /charge
      v
PaymentApp :8081
```

The HTTP call between BookingApp and PaymentApp is the real network boundary.

## 5. Build

From the project root:

```
cd C:\Projects\ticket-booking
.\gradlew.bat clean build --no-daemon
```

If Gradle has a lock listener issue on Windows, use:

```
$env:GRADLE_OPTS="-Dorg.gradle.cache.internal.locklistener=false"
.\gradlew.bat clean build --no-daemon
```

Expected result:

```
BUILD SUCCESSFUL
```

Evidence:

```
docs/evidence/24_final_clean_build_after_recovery.png
```

## 6. Run Payment Service

Terminal A:

```
cd C:\Projects\ticket-booking
java -cp "app\build\classes\java\main" com.ulfg.booking.PaymentApp
```

Expected:

```
[payment] listening on :8081
```

## 7. Run Booking Service

Terminal B:

```
cd C:\Projects\ticket-booking
java -cp "app\build\classes\java\main" com.ulfg.booking.BookingApp
```

Expected:

```
[booking] listening on :8080  workers=32 queueCap=500
```

## 8. Manual Test

Terminal C:

```
Invoke-RestMethod -Uri http://localhost:8081/health
Invoke-RestMethod -Method Post -Uri http://localhost:8080/bookings
Invoke-RestMethod -Uri http://localhost:8080/metrics
Invoke-RestMethod -Uri http://localhost:8080/inventory
```

Expected:

```
status = UP
booking request = ACCEPTED
completed count increases
inventory decreases
```

Evidence:

```
docs/evidence/05_successful_booking_demo.png
```

## 9. Automated Tests

Run:

```
.\gradlew.bat test
```

Test evidence:

* InventoryStressTest passed
* IdempotencyTest passed
* 2 tests, 0 failures, 100% successful

Evidence:

```
docs/evidence/02_tests_100_percent.png
```

## 10. Load Tests

Run while BookingApp and PaymentApp are active:

```
java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 50 2000
java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 100 5000
java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 200 10000
```

Measured results:

| Concurrency | Total Requests |  Throughput |   p50 |   p95 |    p99 | 429 |
| ----------: | -------------: | ----------: | ----: | ----: | -----: | --: |
|          50 |           2000 | 187.4 req/s | 224ms | 488ms |  829ms |   0 |
|         100 |           5000 | 339.7 req/s | 269ms | 419ms |  913ms |   0 |
|         200 |          10000 | 285.2 req/s | 674ms | 958ms | 1753ms |   0 |

Evidence:

```
docs/evidence/07_load_test_50_clients.png
docs/evidence/08_load_test_100_clients.png
docs/evidence/09_load_test_200_clients.png
```

## 11. High Load Stress Tests

| Concurrency | Total Requests |  Throughput |    p50 |    p95 |    p99 |
| ----------: | -------------: | ----------: | -----: | -----: | -----: |
|         500 |          20000 | 331.0 req/s | 1516ms | 2316ms | 2684ms |
|         800 |          30000 | 496.6 req/s | 1664ms | 1955ms | 3245ms |
|        1000 |          50000 | 822.8 req/s | 1768ms | 2414ms | 2877ms |

Evidence:

```
docs/evidence/13_high_load_stress_results.png
```

## 12. Failure Injection

PaymentApp was restarted with artificial latency:

```
$env:PAYMENT_LATENCY_MS="3000"
$env:PAYMENT_FAIL_RATE="0.0"
java -cp "app\build\classes\java\main" com.ulfg.booking.PaymentApp
```

Then the load test was executed:

```
java -cp "app\build\classes\java\main" com.ulfg.booking.LoadTest 1000 50000
```

Observed behavior:

* errors increased under slow payment
* failed bookings increased
* metrics endpoint still responded
* system stayed alive

Evidence:

```
docs/evidence/14_payment_latency_injection_started.png
docs/evidence/16_slow_payment_errors_result.png
docs/evidence/17_slow_payment_metrics.png
```

## 13. CPU Benchmark

Run:

```
java -cp "app\build\classes\java\main" com.ulfg.booking.CpuBenchmark
```

Measured result:

| Tickets | Sequential | Parallel | Same Result | Speedup |
| ------: | ---------: | -------: | ----------- | ------: |
|    1000 |       29ms |     21ms | true        |   1.38x |
|    5000 |       96ms |     40ms | true        |   2.40x |
|   10000 |      143ms |     49ms | true        |   2.92x |
|   20000 |      251ms |     85ms | true        |   2.95x |

Evidence:

```
docs/evidence/19_cpu_benchmark_result.png
```

## 14. Event Log

The booking lifecycle states are:

* PLACED
* RESERVED
* PAYMENT_PENDING
* CONFIRMED
* FAILED
* CANCELLED

EventLog records:

* bookingId
* idempotencyKey
* state
* detail

Example:

```
[event] booking=bk-... key=... -> CONFIRMED charged attempt=3
```

Evidence:

```
docs/evidence/22_event_log_source.png
docs/evidence/23_booking_states_source.png
docs/evidence/27_booking_final_metrics_shutdown.png
```

## 15. Summary

This project demonstrates:

* concurrent HTTP booking requests
* protected shared inventory
* no overselling
* idempotency for retried requests
* real HTTP network boundary between BookingApp and PaymentApp
* event logging and tracing
* timeout/failure behavior
* measured load and stress tests
* sequential vs parallel CPU benchmark
* graceful shutdown
