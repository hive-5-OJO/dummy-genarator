package com.que.telecomdummy.model;

import java.nio.file.Path;
import java.util.List;

public record GenerationContext(
        Path outDir,
        int year,
        List<Integer> months,
        int memberCount,
        long seed,
        int usageChunkRows
) {
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Path outDir;
        private int year;
        private List<Integer> months;
        private int memberCount;
        private long seed;
        private int usageChunkRows;

        public Builder outDir(Path v){ this.outDir = v; return this; }
        public Builder year(int v){ this.year = v; return this; }
        public Builder months(List<Integer> v){ this.months = v; return this; }
        public Builder memberCount(int v){ this.memberCount = v; return this; }
        public Builder seed(long v){ this.seed = v; return this; }
        public Builder usageChunkRows(int v){ this.usageChunkRows = v; return this; }

        public GenerationContext build() {
            if (outDir == null) throw new IllegalStateException("outDir null");
            if (months == null || months.isEmpty()) throw new IllegalStateException("months null/empty");
            return new GenerationContext(outDir, year, months, memberCount, seed, usageChunkRows);
        }
    }
}
