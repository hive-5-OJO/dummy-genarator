package com.que.telecomdummy.util;

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

    public String getString(String key, String def) {
        return map.getOrDefault(key, def);
    }

    public int getInt(String key, int def) {
        String v = map.get(key);
        if (v == null) return def;
        return Integer.parseInt(v);
    }

    public long getLong(String key, long def) {
        String v = map.get(key);
        if (v == null) return def;
        return Long.parseLong(v);
    }

    public static List<Integer> parseMonthRange(String s) {
        // "1-12" or "3-8" or "5"
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
}
