package com.que.telecomdummy.util;

import java.util.Random;

public final class RandomUtil {
    private RandomUtil() {}

    public static int nextIntInclusive(Random r, int min, int max) {
        if (max < min) throw new IllegalArgumentException("max < min");
        return min + r.nextInt(max - min + 1);
    }

    public static long nextLongInclusive(Random r, long min, long max) {
        if (max < min) throw new IllegalArgumentException("max < min");
        // Avoid overflow in (max - min + 1)
        long bound = max - min + 1;
        if (bound <= 0) {
            // Range is too large; fall back to rejection sampling.
            long x;
            do {
                x = r.nextLong();
            } while (x < min || x > max);
            return x;
        }
        long n = nextLongBounded(r, bound);
        return min + n;
    }

    /** Returns 0 <= x < bound (bound must be > 0). */
    private static long nextLongBounded(Random r, long bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be > 0");
        // Java 8 style bounded long
        long m = bound - 1;
        long x = r.nextLong();
        if ((bound & m) == 0L) {
            // power of two
            return x & m;
        }
        long u = x >>> 1;
        while (u + m - (u % bound) < 0L) {
            u = (r.nextLong() >>> 1);
        }
        return u % bound;
    }

    public static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
    // CSV null(빈칸) 허용을 위해 String 반환
    public static String maybeScore(Random r) {
        // 10~20%는 NULL(빈칸)로 둔다
        if (r.nextDouble() < 0.15) return "";
        return Integer.toString(1 + r.nextInt(10));
    }

}
