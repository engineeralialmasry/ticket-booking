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
docs/evidence/final_clean_build_after_recovery.png
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
docs/evidence/successful_booking_demo.png
```

## 9. Automated Tests

Run:

```
.\gradlew.bat test
```

Evidence:

```
docs/evidence/final_clean_build_after_recovery
```

Test evidence:

* InventoryStressTest passed
* IdempotencyTest passed
* 2 tests, 0 failures, 100% successful



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
|          50 |           2000 | 407.2 req/s | 104ms | 213ms |  474ms |   0 |
|         100 |           5000 | 510.7 req/s | 175ms | 265ms |  684ms |   0 |
|         200 |          10000 | 518.7 req/s | 344ms | 460ms | 1333ms |   0 |

Evidence:

```
docs/evidence/load_tests_50_100_200_clients.png
```

## 11. High Load Stress Tests

| Concurrency | Total Requests |  Throughput |    p50 |    p95 |    p99 |
| ----------: | -------------: | ----------: | -----: | -----: | -----: |
|         500 |          20000 | 579.9 req/s | 987ms | 1548ms | 1978ms |
|         800 |          30000 | 500.1 req/s | 1558ms | 1779ms | 2318ms |
|        1000 |          50000 | 828.4 req/s | 1939ms | 2519ms | 2927ms |

Evidence:

```
docs/evidence/high_load_stress_results.png
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
docs/evidence/slow_payment_errors_latency_metrics_result.png
```

## 13. CPU Benchmark

Run:

```
java -cp "app\build\classes\java\main" com.ulfg.booking.CpuBenchmark
```

Measured result:

| Tickets | Sequential | Parallel | Same Result | Speedup |
| ------: | ---------: | -------: | ----------- | ------: |
|    1000 |       19ms |     9ms | true        |   2.11x |
|    5000 |       69ms |     15ms | true        |   4.60x |
|   10000 |      114ms |     31ms | true        |   3.68x |
|   20000 |      231ms |     56ms | true        |   4.13x |

Evidence:

```
docs/evidence/cpu_benchmark_result.png
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
