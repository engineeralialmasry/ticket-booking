/*
 * Decompiled with CFR 0.152.
 */
package com.ulfg.booking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public class PaymentApp {
    private static final int PORT = PaymentApp.envInt("PAYMENT_PORT", 8081);
    private static final double FAIL_RATE = PaymentApp.envDouble("PAYMENT_FAIL_RATE", 0.0);
    private static final long LATENCY_MS = PaymentApp.envLong("PAYMENT_LATENCY_MS", 0L);
    private static final AtomicLong charged = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/charge", PaymentApp::handleCharge);
        server.createContext("/health", PaymentApp::handleHealth);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[payment] shutting down...");
            server.stop(2);
            System.out.printf("[payment] final: charged=%d failed=%d%n", charged.get(), failed.get());
        }));
        server.start();
        System.out.printf("[payment] listening on :%d  failRate=%.2f latencyMs=%d%n", PORT, FAIL_RATE, LATENCY_MS);
    }

    private static void handleCharge(HttpExchange ex) throws IOException {
        if (LATENCY_MS > 0L) {
            try {
                Thread.sleep(LATENCY_MS);
            }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (ThreadLocalRandom.current().nextDouble() < FAIL_RATE) {
            failed.incrementAndGet();
            PaymentApp.send(ex, 500, "{\"status\":\"DECLINED\",\"reason\":\"injected_failure\"}");
            return;
        }
        long n = charged.incrementAndGet();
        PaymentApp.send(ex, 200, "{\"status\":\"CHARGED\",\"txn\":" + n + "}");
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        PaymentApp.send(ex, 200, "{\"status\":\"UP\"}");
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody();){
            os.write(bytes);
        }
    }

    private static int envInt(String k, int d) {
        String v = System.getenv(k);
        return v == null ? d : Integer.parseInt(v.trim());
    }

    private static long envLong(String k, long d) {
        String v = System.getenv(k);
        return v == null ? d : Long.parseLong(v.trim());
    }

    private static double envDouble(String k, double d) {
        String v = System.getenv(k);
        return v == null ? d : Double.parseDouble(v.trim());
    }
}
