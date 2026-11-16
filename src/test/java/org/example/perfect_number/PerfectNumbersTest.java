package org.example.perfect_number;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PerfectNumbersTest {
    @Test
    void recognizesPerfectDeficientAndAbundantNumbers() {
        for (int number : new int[]{6, 28, 496, 8128}) {
            assertEquals(number, PerfectNumbers.divisorSum(number));
        }
        for (int number : new int[]{1, 2, 8}) {
            assertTrue(PerfectNumbers.divisorSum(number) < number);
        }
        for (int number : new int[]{12, 18}) {
            assertTrue(PerfectNumbers.divisorSum(number) > number);
        }
        assertEquals(0, PerfectNumbers.divisorSum(1));
        assertEquals(55, PerfectNumbers.divisorSum(36));
        assertEquals(1, PerfectNumbers.divisorSum(Integer.MAX_VALUE));
        // 2,000,000,000 = 2^10 * 5^9; the proper-divisor sum excludes the number itself.
        assertEquals(2_997_558_082L, PerfectNumbers.divisorSum(2_000_000_000));
    }

    @Test
    void matchesTheKnownTotalsThrough28() throws InterruptedException {
        AtomicLong progress = new AtomicLong();
        var result = PerfectNumbers.calculate(new PerfectNumbers.Range(1, 28), completed -> {
            assertEquals(progress.incrementAndGet(), completed);
        });
        assertEquals(List.of(6, 28), result.perfectNumbers());
        assertEquals(22, result.deficientCount());
        assertEquals(4, result.abundantCount());
        assertEquals(28, progress.get());
    }

    @Test
    void coversEachInputExactlyOnceWithDeterministicRanges() {
        for (int num = 1; num <= 200; num++) {
            for (int cnt = 1; cnt <= Math.min(num, 64); cnt++) {
                var ranges = PerfectNumbers.partition(num, cnt);
                assertEquals(cnt, ranges.size());
                long next = 1;
                for (var range : ranges) {
                    assertEquals(next, range.start());
                    assertTrue(range.size() >= 1);
                    next = (long) range.end() + 1;
                }
                assertEquals((long) num + 1, next);
                assertEquals(ranges, PerfectNumbers.partition(num, cnt));
                if (cnt > 1 && num > cnt) {
                    assertTrue(ranges.stream().map(PerfectNumbers.Range::size).distinct().count() > 1,
                            "Unexpected equal workloads: num=" + num + ", cnt=" + cnt);
                }
            }
        }
    }

    @Test
    void allocatesUnequalWorkAndHandlesBoundaryInputs() throws InterruptedException {
        assertEquals(List.of(new PerfectNumbers.Range(1, 3),
                new PerfectNumbers.Range(4, 9), new PerfectNumbers.Range(10, 17),
                new PerfectNumbers.Range(18, 28)), PerfectNumbers.partition(28, 4));
        assertEquals(List.of(new PerfectNumbers.Range(1, 28)), PerfectNumbers.partition(28, 1));
        assertEquals(List.of(new PerfectNumbers.Range(1, 1),
                new PerfectNumbers.Range(2, 2)), PerfectNumbers.partition(2, 2));
        var maximum = PerfectNumbers.partition(Integer.MAX_VALUE, 64);
        assertEquals(Integer.MAX_VALUE, maximum.getLast().end());
        assertEquals((long) Integer.MAX_VALUE, maximum.stream().mapToLong(PerfectNumbers.Range::size).sum());
        var lastNumber = PerfectNumbers.calculate(
                new PerfectNumbers.Range(Integer.MAX_VALUE, Integer.MAX_VALUE), ignored -> {});
        assertEquals(1, lastNumber.deficientCount());
        assertTrue(lastNumber.perfectNumbers().isEmpty());
    }

    @Test
    void parallelResultsAgreeAcrossThreadCounts() throws Exception {
        var expected = PerfectNumbers.calculate(new PerfectNumbers.Range(1, 10_000), ignored -> {});
        for (int count : new int[]{1, 4, 16, 64}) {
            try (var executor = Executors.newFixedThreadPool(count)) {
                List<Future<PerfectNumbers.Result>> futures = new ArrayList<>();
                for (var range : PerfectNumbers.partition(10_000, count)) {
                    futures.add(executor.submit(() -> PerfectNumbers.calculate(range, ignored -> {})));
                }
                List<Integer> perfect = new ArrayList<>();
                long deficient = 0;
                long abundant = 0;
                for (var future : futures) {
                    var result = future.get();
                    perfect.addAll(result.perfectNumbers());
                    deficient += result.deficientCount();
                    abundant += result.abundantCount();
                }
                perfect.sort(Integer::compareTo);
                assertEquals(expected, new PerfectNumbers.Result(perfect, deficient, abundant));
                assertEquals(10_000, perfect.size() + deficient + abundant);
            }
        }
    }

    @Test
    void rejectsInvalidRangesAndThreadCounts() {
        assertThrows(IllegalArgumentException.class, () -> PerfectNumbers.partition(0, 1));
        assertThrows(IllegalArgumentException.class, () -> PerfectNumbers.partition(10, 0));
        assertThrows(IllegalArgumentException.class, () -> PerfectNumbers.partition(10, 11));
        assertThrows(IllegalArgumentException.class, () -> PerfectNumbers.partition(100, 65));
        assertThrows(IllegalArgumentException.class, () -> new PerfectNumbers.Range(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PerfectNumbers.Range(2, 1));
        assertThrows(IllegalArgumentException.class, () -> PerfectNumbers.divisorSum(0));
    }

    @Test
    void interruptionStopsAnActiveCalculation() {
        try {
            assertThrows(InterruptedException.class, () -> PerfectNumbers.calculate(
                    new PerfectNumbers.Range(1, 1000), completed -> {
                        if (completed == 5) {
                            Thread.currentThread().interrupt();
                        }
                    }));
        } finally {
            Thread.interrupted();
        }
    }
}
