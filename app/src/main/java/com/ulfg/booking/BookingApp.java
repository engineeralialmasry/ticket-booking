package com.ulfg.booking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ulfg.booking.core.BookingPipeline;
import com.ulfg.booking.core.EventLog;
import com.ulfg.booking.core.IdempotencyStore;
import com.ulfg.booking.core.InventoryManager;
import com.ulfg.booking.metrics.Metrics;
import com.ulfg.booking.model.BookingRequest;
import com.ulfg.booking.payment.PaymentClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class BookingApp {
    private static final int PORT = 8080;
    private static final int WORKERS = 32;
    private static final int QUEUE_CAP = 500;
    private static final String PAYMENT_URL = "http://localhost:8081";

    public static void main(String[] args) throws IOException {
        InventoryManager inventory = new InventoryManager();
        IdempotencyStore idempotency = new IdempotencyStore();
        EventLog eventLog = new EventLog();
        Metrics metrics = new Metrics();

        inventory.initEvent("concertA", 1_000_000);

        ThreadPoolExecutor workers = new ThreadPoolExecutor(
                WORKERS,
                WORKERS,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAP),
                new ThreadPoolExecutor.AbortPolicy()
        );

        ExecutorService paymentIo = Executors.newFixedThreadPool(16);

        PaymentClient payment = new PaymentClient(
                PAYMENT_URL,
                paymentIo,
                Duration.ofMillis(300L),
                3
        );

        BookingPipeline pipeline = new BookingPipeline(
                inventory,
                idempotency,
                eventLog,
                payment,
                workers
        );

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/bookings", ex ->
                handleBooking(ex, pipeline, metrics, workers)
        );

        server.createContext("/metrics", ex -> {
            metrics.queueDepth(workers.getQueue().size());
            respond(ex, 200, metrics.snapshot().pretty());
        });

        server.createContext("/inventory", ex ->
                respond(ex, 200, "concertA remaining=" + inventory.remaining("concertA"))
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[booking] shutting down...");
            server.stop(2);
            workers.shutdown();
            paymentIo.shutdown();

            try {
                workers.awaitTermination(5L, TimeUnit.SECONDS);
                paymentIo.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("[booking] final metrics: " + metrics.snapshot().pretty());
        }));

        server.start();

        System.out.printf(
                "[booking] listening on :%d  workers=%d queueCap=%d%n",
                PORT,
                WORKERS,
                QUEUE_CAP
        );
    }

    private static void handleBooking(
            HttpExchange ex,
            BookingPipeline pipeline,
            Metrics metrics,
            ThreadPoolExecutor workers
    ) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, "method not allowed");
            return;
        }

        String idemKey = ex.getRequestHeaders().getFirst("Idempotency-Key");

        if (idemKey == null || idemKey.isBlank()) {
            idemKey = UUID.randomUUID().toString();
        }

        BookingRequest req = new BookingRequest(idemKey, "concertA", 1);
        long start = System.currentTimeMillis();

        try {
            pipeline.submit(req).whenComplete((outcome, err) -> {
                long latency = System.currentTimeMillis() - start;

                if (err == null && outcome.finalState().name().equals("CONFIRMED")) {
                    metrics.recordCompleted(latency);
                } else {
                    metrics.recordFailed(latency);
                }
            });

            metrics.queueDepth(workers.getQueue().size());

            respond(
                    ex,
                    202,
                    "{\"status\":\"ACCEPTED\",\"idempotencyKey\":\"" + idemKey + "\"}"
            );
        } catch (RejectedExecutionException rej) {
            metrics.recordRejected();

            respond(
                    ex,
                    429,
                    "{\"status\":\"REJECTED\",\"reason\":\"queue_full\"}"
            );
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}