# Concurrency Scorecard

| Question               | Answer                                                                                     | Evidence                          |
| ---------------------- | ------------------------------------------------------------------------------------------ | --------------------------------- |
| Thread-safe?           | Yes. Shared inventory is protected using per-event locks.                                  | InventoryManager.java             |
| Visibility guaranteed? | Yes. Inventory updates happen inside synchronized blocks.                                  | InventoryManager.java             |
| No overselling?        | Yes. Stress test passed with concurrent reservations.                                      | InventoryStressTest / test report |
| Duplicate retry safe?  | Yes. IdempotencyStore prevents duplicate bookings for the same key.                        | IdempotencyTest                   |
| Bounded resources?     | Yes. BookingApp uses a fixed worker pool and bounded queue.                                | BookingApp.java                   |
| Real network boundary? | Yes. BookingApp calls PaymentApp through HTTP on port 8081.                                | PaymentClient.java                |
| Failure recovery?      | Yes. If payment fails, the system releases reserved seats and marks booking as FAILED.     | BookingPipeline.java              |
| Observable?            | Yes. Metrics show completed, failed, rejected, queue depth, throughput, p50, p95, and p99. | /metrics endpoint                 |
| Event tracing?         | Yes. EventLog records bookingId, idempotencyKey, state, timestamp, and detail.             | EventLog.java                     |
| Graceful shutdown?     | Yes. BookingApp and PaymentApp print final metrics/counters when stopped.                  | shutdown screenshots              |

## Summary

The system satisfies the main concurrency requirements:

* concurrent booking requests
* protected shared inventory
* no overselling
* idempotency for retried requests
* bounded worker execution
* real HTTP network boundary
* failure injection and recovery behavior
* metrics and event logging
* graceful shutdown

## Limitation

During extreme overload, the captured results showed client errors and failed bookings, but HTTP 429 rejection was not always observed. The system still stayed alive, counted failures, and kept the metrics endpoint responsive.

A future improvement would tune the queue capacity and rejection behavior to make HTTP 429 backpressure easier to observe.
