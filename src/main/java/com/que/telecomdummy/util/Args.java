package com.que.telecomdummy.util;

import java.time.YearMonth;
import java.util.*;

public final class Args {
    private final Map<String, String> map;

    private Args(Map<String, String> map) { this.map = map; }

    public static Args parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) continue;
            String v = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                v = args[i + 1];
                i++;
            }
            m.put(k, v);
        }
        return new Args(m);
    }

    public boolean has(String key) { return map.containsKey(key); }
    public boolean hasAny(List<String> keys) { return keys.stream().anyMatch(map::containsKey); }

    public String getString(String key, String def) {
        String v = map.get(key);
        return v == null ? def : v;
    }
    public String getStringAny(List<String> keys, String def) {
        for (String k : keys) {
            if (map.containsKey(k)) return map.get(k);
        }
        return def;
    }
    public int getInt(String key, int def) {
        String v = map.get(key);
        if (v == null) return def;
        return Integer.parseInt(v);
    }
    public int getIntAny(List<String> keys, int def) {
        for (String k : keys) {
            if (map.containsKey(k)) return Integer.parseInt(map.get(k));
        }
        return def;
    }
    public long getLong(String key, long def) {
        String v = map.get(key);
        if (v == null) return def;
        return Long.parseLong(v);
    }
    public long getLongAny(List<String> keys, long def) {
        for (String k : keys) {
            if (map.containsKey(k)) return Long.parseLong(map.get(k));
        }
        return def;
    }

    public static List<Integer> parseMonthRange(String s) {
        s = s.trim();
        if (s.contains("-")) {
            String[] p = s.split("-", 2);
            int a = Integer.parseInt(p[0].trim());
            int b = Integer.parseInt(p[1].trim());
            if (a < 1 || b > 12 || a > b) throw new IllegalArgumentException("Invalid --months: " + s);
            List<Integer> months = new ArrayList<>();
            for (int m = a; m <= b; m++) months.add(m);
            return months;
        } else {
            int m = Integer.parseInt(s);
            if (m < 1 || m > 12) throw new IllegalArgumentException("Invalid --months: " + s);
            return List.of(m);
        }
    }

    public static YearMonth parseYearMonth(String s) {
        try { return YearMonth.parse(s.trim()); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid YearMonth value: " + s + " (expected yyyy-MM)", e); }
    }

    public static List<YearMonth> toPeriods(YearMonth from, YearMonth to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("--from must be <= --to");
        List<YearMonth> out = new ArrayList<>();
        for (YearMonth ym = from; !ym.isAfter(to); ym = ym.plusMonths(1)) out.add(ym);
        return out;
    }
}
