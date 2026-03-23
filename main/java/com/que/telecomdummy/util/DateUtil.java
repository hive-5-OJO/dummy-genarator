package com.que.telecomdummy.util;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public final class DateUtil {
    private DateUtil() {}

    public static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String ym(int year, int month) {
        return String.format("%04d%02d", year, month);
    }

    public static int daysInMonthFixedFeb28(int year, int month) {
        if (month == 2) return 28;
        return YearMonth.of(year, month).lengthOfMonth();
    }

    public static LocalDate randomDate(Random r, LocalDate startInclusive, LocalDate endInclusive) {
        long start = startInclusive.toEpochDay();
        long end = endInclusive.toEpochDay();
        long d = start + (long)(r.nextDouble() * (end - start + 1));
        return LocalDate.ofEpochDay(d);
    }

    public static LocalDateTime atFixed(int year, int month, int day, int hour, int min, int sec) {
        return LocalDateTime.of(year, month, day, hour, min, sec);
    }
}
