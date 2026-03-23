package com.que.telecomdummy.util;

import java.util.*;

public final class WeightedPicker<T> {
    private final List<T> items = new ArrayList<>();
    private final double[] prefix;
    private final double total;

    public static final class Entry<T> {
        public final T item;
        public final double weight;
        public Entry(T item, double weight) {
            this.item = item;
            this.weight = weight;
        }
    }

    public WeightedPicker(List<Entry<T>> entries) {
        if (entries.isEmpty()) throw new IllegalArgumentException("entries empty");
        prefix = new double[entries.size()];
        double sum = 0.0;
        for (int i = 0; i < entries.size(); i++) {
            Entry<T> e = entries.get(i);
            if (e.weight <= 0) throw new IllegalArgumentException("weight must be > 0");
            items.add(e.item);
            sum += e.weight;
            prefix[i] = sum;
        }
        total = sum;
    }

    public T pick(Random r) {
        double x = r.nextDouble() * total;
        int idx = Arrays.binarySearch(prefix, x);
        if (idx < 0) idx = -idx - 1;
        if (idx >= items.size()) idx = items.size() - 1;
        return items.get(idx);
    }
}
