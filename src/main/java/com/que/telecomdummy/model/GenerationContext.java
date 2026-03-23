package com.que.telecomdummy.model;

import com.que.telecomdummy.util.Args;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record GenerationContext(
        Path outDir,
        int year,
        List<Integer> months,
        int memberCount,
        long seed,
        int usageChunkRows,
        YearMonth fromYm,
        YearMonth toYm,
        List<YearMonth> periods
) {
    public static Builder builder() { return new Builder(); }

    public boolean includes(YearMonth ym) {
        return !ym.isBefore(fromYm) && !ym.isAfter(toYm);
    }

    public static final class Builder {
        private Path outDir;
        private int year;
        private List<Integer> months;
        private int memberCount;
        private long seed;
        private int usageChunkRows;
        private YearMonth fromYm;
        private YearMonth toYm;
        private List<YearMonth> periods;

        public Builder outDir(Path v){ this.outDir = v; return this; }
        public Builder year(int v){ this.year = v; return this; }
        public Builder months(List<Integer> v){ this.months = v; return this; }
        public Builder memberCount(int v){ this.memberCount = v; return this; }
        public Builder seed(long v){ this.seed = v; return this; }
        public Builder usageChunkRows(int v){ this.usageChunkRows = v; return this; }
        public Builder fromYm(YearMonth v){ this.fromYm = v; return this; }
        public Builder toYm(YearMonth v){ this.toYm = v; return this; }
        public Builder periods(List<YearMonth> v){ this.periods = v; return this; }

        public GenerationContext build() {
            if (outDir == null) throw new IllegalStateException("outDir null");

            YearMonth resolvedFrom = fromYm;
            YearMonth resolvedTo = toYm;
            if (resolvedFrom == null || resolvedTo == null) {
                if (months == null || months.isEmpty()) throw new IllegalStateException("months null/empty");
                if (year <= 0) throw new IllegalStateException("year invalid");
                resolvedFrom = YearMonth.of(year, months.get(0));
                resolvedTo = YearMonth.of(year, months.get(months.size() - 1));
            }
            if (resolvedFrom.isAfter(resolvedTo)) throw new IllegalStateException("fromYm > toYm");

            List<YearMonth> resolvedPeriods = periods;
            if (resolvedPeriods == null || resolvedPeriods.isEmpty()) {
                resolvedPeriods = Args.toPeriods(resolvedFrom, resolvedTo);
            }

            List<Integer> resolvedMonths = months;
            if (resolvedMonths == null || resolvedMonths.isEmpty()) {
                resolvedMonths = new ArrayList<>();
                if (resolvedFrom.getYear() == resolvedTo.getYear()) {
                    for (int m = resolvedFrom.getMonthValue(); m <= resolvedTo.getMonthValue(); m++) resolvedMonths.add(m);
                } else {
                    for (int m = 1; m <= 12; m++) resolvedMonths.add(m);
                }
            }

            int resolvedYear = (year > 0) ? year : resolvedTo.getYear();
            return new GenerationContext(
                    outDir,
                    resolvedYear,
                    Collections.unmodifiableList(new ArrayList<>(resolvedMonths)),
                    memberCount,
                    seed,
                    usageChunkRows,
                    resolvedFrom,
                    resolvedTo,
                    Collections.unmodifiableList(new ArrayList<>(resolvedPeriods))
            );
        }
    }
}
