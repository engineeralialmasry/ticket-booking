package com.ulfg.booking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LoadTest {
    public static void main(String[] args) throws Exception {
        int concurrency = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int total = args.length > 1 ? Integer.parseInt(args[1]) : 2000;

        URI uri = URI.create("http://localhost:8080/bookings");

        ExecutorService httpExecutor = Executors.newFixedThreadPool(concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .executor(httpExecutor)
                .build();

        CountDownLatch done = new CountDownLatch(total);

        long[] latencies = new long[total];
        AtomicInteger idx = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicLong errors = new AtomicLong();

        long startAll = System.nanoTime();

        for (int i = 0; i < total; i++) {
            pool.submit(() -> {
                long t0 = System.nanoTime();

                try {
                    HttpRequest req = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                    long latencyMs = (System.nanoTime() - t0) / 1_000_000L;
                    latencies[idx.getAndIncrement()] = latencyMs;

                    if (resp.statusCode() == 202) {
                        accepted.incrementAndGet();
                    } else if (resp.statusCode() == 429) {
                        rejected.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    latencies[idx.getAndIncrement()] = -1L;
                } finally {
                    done.countDown();
                }
            });
        }

        done.await(60L, TimeUnit.SECONDS);

        double elapsedSec = (System.nanoTime() - startAll) / 1_000_000_000.0;

        pool.shutdown();
        httpExecutor.shutdown();

        long[] ok = Arrays.stream(latencies)
                .filter(v -> v >= 0L)
                .sorted()
                .toArray();

        System.out.println();
        System.out.println("========== LOAD TEST RESULTS ==========");
        System.out.printf("concurrency=%d total=%d elapsed=%.2fs%n", concurrency, total, elapsedSec);
        System.out.printf("accepted=%d rejected(429)=%d errors=%d%n", accepted.get(), rejected.get(), errors.get());
        System.out.printf("throughput=%.1f req/s%n", total / elapsedSec);
        System.out.printf(
                "p50=%dms  p95=%dms  p99=%dms  max=%dms%n",
                pct(ok, 50.0),
                pct(ok, 95.0),
                pct(ok, 99.0),
                ok.length > 0 ? ok[ok.length - 1] : 0L
        );
        System.out.println("=======================================");
        System.out.println();
    }

    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0L;
        }

        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(i, sorted.length - 1))];
    }
}