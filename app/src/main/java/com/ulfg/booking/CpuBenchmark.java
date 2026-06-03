package com.ulfg.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

public class CpuBenchmark {
    private static final int HASH_ROUNDS = 150;

    public static void main(String[] args) {
        int processors = Runtime.getRuntime().availableProcessors();
        int parallelism = Math.max(2, processors - 1);

        System.out.println("========== CPU BENCHMARK ==========");
        System.out.println("CPU-bound step: ticket hash/signature generation");
        System.out.println("availableProcessors=" + processors);
        System.out.println("parallelism=" + parallelism);
        System.out.println("hashRoundsPerTicket=" + HASH_ROUNDS);
        System.out.println();

        int[] sizes = {1000, 5000, 10000, 20000};

        sequential(500);
        parallel(500, parallelism);

        System.out.printf(
                "%-12s %-18s %-18s %-12s %-12s%n",
                "tickets",
                "sequential(ms)",
                "parallel(ms)",
                "same?",
                "speedup"
        );

        for (int n : sizes) {
            long startSeq = System.nanoTime();
            long seqResult = sequential(n);
            long seqMs = (System.nanoTime() - startSeq) / 1_000_000L;

            long startPar = System.nanoTime();
            long parResult = parallel(n, parallelism);
            long parMs = (System.nanoTime() - startPar) / 1_000_000L;

            boolean same = seqResult == parResult;
            double speedup = parMs == 0L ? 0.0 : (double) seqMs / (double) parMs;

            System.out.printf(
                    "%-12d %-18d %-18d %-12s %.2fx%n",
                    n,
                    seqMs,
                    parMs,
                    same,
                    speedup
            );
        }

        System.out.println("===================================");
        System.out.println("Interpretation:");
        System.out.println("Sequential is the correctness baseline.");
        System.out.println("Parallel may win for larger inputs, but for small inputs overhead can dominate.");
    }

    private static long sequential(int tickets) {
        long checksum = 0L;

        for (int i = 0; i < tickets; i++) {
            checksum += ticketHashScore(i);
        }

        return checksum;
    }

    private static long parallel(int tickets, int parallelism) {
        try (ForkJoinPool pool = new ForkJoinPool(parallelism)) {
            return pool.submit(() ->
                    IntStream.range(0, tickets)
                            .parallel()
                            .mapToLong(CpuBenchmark::ticketHashScore)
                            .sum()
            ).join();
        }
    }

    private static long ticketHashScore(int ticketId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] data = ("ticket-" + ticketId + "-concertA-secure-signature")
                    .getBytes(StandardCharsets.UTF_8);

            for (int i = 0; i < HASH_ROUNDS; i++) {
                data = digest.digest(data);
            }

            long score = 0L;

            for (int i = 0; i < Math.min(8, data.length); i++) {
                score = (score << 8) ^ (long) (data[i] & 0xFF);
            }

            return score;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}