package org.example.perfect_number;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongConsumer;

/** Number calculations and workload allocation, independent of JavaFX. */
public final class PerfectNumbers {
    public static final int MAX_THREADS = 64;

    private PerfectNumbers() {
    }

    public record Range(int start, int end) {
        public Range {
            if (start < 1 || end < start) {
                throw new IllegalArgumentException("Invalid positive-integer range.");
            }
        }

        public long size() {
            return (long) end - start + 1;
        }
    }

    public record Result(List<Integer> perfectNumbers, long deficientCount, long abundantCount) {
        public Result {
            perfectNumbers = List.copyOf(perfectNumbers);
        }
    }

    /** Each worker receives one number, plus a share weighted by 1, 2, ..., cnt. */
    public static List<Range> partition(int num, int cnt) {
        if (num < 1) {
            throw new IllegalArgumentException("Upper limit is not a positive integer.");
        }
        if (cnt < 1 || cnt > Math.min(num, MAX_THREADS)) {
            throw new IllegalArgumentException("Threads outside valid range (1 to "
                    + Math.min(num, MAX_THREADS) + ").");
        }

        long remaining = (long) num - cnt;
        long totalWeight = (long) cnt * (cnt + 1) / 2;
        long[] sizes = new long[cnt];
        long[] remainders = new long[cnt];
        List<Integer> order = new ArrayList<>();
        long allocated = 0;
        for (int i = 0; i < cnt; i++) {
            long weighted = remaining * (i + 1);
            sizes[i] = 1 + weighted / totalWeight;
            remainders[i] = weighted % totalWeight;
            allocated += sizes[i];
            order.add(i);
        }
        // Largest remainders receive the leftover numbers; higher weights break ties.
        order.sort(Comparator.<Integer>comparingLong(i -> remainders[i]).reversed()
                .thenComparing(Comparator.reverseOrder()));
        for (int i = 0; i < num - allocated; i++) {
            sizes[order.get(i)]++;
        }
        // Rounding can make small workloads equal (for example, num=4 and cnt=2).
        // The adjustment preserves nonempty ranges and unequal workloads.
        if (cnt > 1 && sizes[0] > 1 && Arrays.stream(sizes).allMatch(size -> size == sizes[0])) {
            sizes[0]--;
            sizes[cnt - 1]++;
        }

        List<Range> ranges = new ArrayList<>();
        long start = 1;
        for (long size : sizes) {
            ranges.add(new Range((int) start, (int) (start + size - 1)));
            start += size;
        }
        return List.copyOf(ranges);
    }

    public static long divisorSum(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("Number is not positive.");
        }
        if (number == 1) {
            return 0;
        }
        long sum = 1;
        for (int divisor = 2; divisor <= number / divisor; divisor++) {
            if (number % divisor == 0) {
                sum += divisor;
                int partner = number / divisor;
                if (partner != divisor) {
                    sum += partner;
                }
            }
        }
        return sum;
    }

    public static Result calculate(Range range, LongConsumer progress) throws InterruptedException {
        List<Integer> perfect = new ArrayList<>();
        long deficient = 0;
        long abundant = 0;
        // A long loop counter prevents wrapping after Integer.MAX_VALUE.
        for (long number = range.start(); number <= range.end(); number++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Calculation interrupted.");
            }
            long sum = divisorSum((int) number);
            if (sum == number) {
                perfect.add((int) number);
            } else if (sum < number) {
                deficient++;
            } else {
                abundant++;
            }
            progress.accept(number - range.start() + 1);
        }
        return new Result(perfect, deficient, abundant);
    }
}
