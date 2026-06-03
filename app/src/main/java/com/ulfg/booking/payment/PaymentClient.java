package com.ulfg.booking.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PaymentClient {
    private final HttpClient http;
    private final URI chargeUri;
    private final Duration perCallTimeout;
    private final int maxAttempts;

    public PaymentClient(String baseUrl, ExecutorService executor, Duration perCallTimeout, int maxAttempts) {
        this.http = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(perCallTimeout)
                .build();

        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chargeUri = URI.create(normalized + "/charge");
        this.perCallTimeout = perCallTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public CompletableFuture<PaymentResult> charge(String bookingId, int amount) {
        return attempt(bookingId, amount, 1);
    }

    private CompletableFuture<PaymentResult> attempt(String bookingId, int amount, int attemptNumber) {
        String body = "bookingId=" + bookingId + "&amount=" + amount;

        HttpRequest request = HttpRequest.newBuilder(chargeUri)
                .timeout(perCallTimeout)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error == null && response != null &&
                            response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.completedFuture(
                                new PaymentResult(true, "charged attempt=" + attemptNumber)
                        );
                    }

                    String reason;
                    if (error != null) {
                        reason = rootMsg(error);
                    } else if (response != null) {
                        reason = "status=" + response.statusCode() + " body=" + response.body();
                    } else {
                        reason = "unknown payment error";
                    }

                    if (attemptNumber < maxAttempts) {
                        long backoffMs = 100L * attemptNumber;
                        System.out.printf(
                                "[payment-client] booking=%s attempt %d failed (%s), retrying in %dms%n",
                                bookingId, attemptNumber, reason, backoffMs
                        );

                        return delay(backoffMs)
                                .thenCompose(v -> attempt(bookingId, amount, attemptNumber + 1));
                    }

                    System.out.printf(
                            "[payment-client] booking=%s exhausted %d attempts (%s)%n",
                            bookingId, maxAttempts, reason
                    );

                    return CompletableFuture.completedFuture(
                            new PaymentResult(false, "payment failed after " + maxAttempts + " attempts: " + reason)
                    );
                })
                .thenCompose(future -> future);
    }

    private static CompletableFuture<Void> delay(long millis) {
        ExecutorService sleeper = Executors.newSingleThreadExecutor();

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, sleeper);

        return future.whenComplete((v, e) -> sleeper.shutdown());
    }

    private static String rootMsg(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    public record PaymentResult(boolean success, String detail) {
    }
}
