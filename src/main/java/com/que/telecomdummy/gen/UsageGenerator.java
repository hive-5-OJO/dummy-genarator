package com.que.telecomdummy.gen;

import com.que.telecomdummy.model.GenerationContext;
import com.que.telecomdummy.model.MemberProfile;
import com.que.telecomdummy.model.Segment.Archetype;
import com.que.telecomdummy.model.Segment.UsageSegment;
import com.que.telecomdummy.config.GenerationPolicy;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;
import com.que.telecomdummy.util.RandomUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

public final class UsageGenerator {
    private final GenerationContext ctx;
    private final Random r;
    private final MemberGenerator memberGen;
    private final GenerationPolicy policy;

    private long nextUsageId = 1;

    public UsageGenerator(GenerationContext ctx, MemberGenerator memberGen, GenerationPolicy policy) {
        this.ctx = ctx;
        this.memberGen = memberGen;
        this.policy = (policy == null) ? GenerationPolicy.defaults() : policy;
        this.r = new Random(ctx.seed() ^ 0x51A9E123L);
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("usage");
        Files.createDirectories(outDir);

        for (YearMonth ym : ctx.periods()) {
            int year = ym.getYear();
            int month = ym.getMonthValue();
            Path file = outDir.resolve("data_usage_" + DateUtil.ym(ym) + ".csv");
            try (CsvWriter w = new CsvWriter(file, List.of(
                    "data_usage_id","member_id","usage_date","usage_time","usage_amount","region","created_at"
            ))) {
                int daysInMonth = DateUtil.daysInMonthFixedFeb28(year, month);

                for (MemberProfile m : memberGen.members()) {
                    if (!isActiveInMonth(m, ym)) continue;

                    int activeDays = pickActiveDays(m, ym, daysInMonth);
                    if ("DORMANT".equalsIgnoreCase(m.status())) {
                        activeDays = (int) Math.floor(activeDays * policy.dormantUsageMultiplier);
                    }
                    if (activeDays <= 0) continue;

                    int minDay = 1;
                    if (YearMonth.from(m.createdAt()).equals(ym)) {
                        minDay = m.createdAt().getDayOfMonth();
                    }
                    if (minDay > daysInMonth) continue;

                    int available = daysInMonth - minDay + 1;
                    if (activeDays > available) activeDays = available;

                    Set<Integer> days = new HashSet<>();
                    while (days.size() < activeDays) {
                        days.add(RandomUtil.nextIntInclusive(r, minDay, daysInMonth));
                    }

                    for (int day : days) {
                        int hour = pickHour(m);
                        int minute = RandomUtil.nextIntInclusive(r, 0, 59);
                        int second = RandomUtil.nextIntInclusive(r, 0, 59);
                        LocalDateTime t = LocalDateTime.of(year, month, day, hour, minute, second);

                        boolean vip = isVipLike(m);
                        long usageMb = sampleUsageAmountMb(m.usageSegment(), vip, m.archetype());
                        if ("DORMANT".equalsIgnoreCase(m.status())) {
                            usageMb = (long) Math.floor(usageMb * policy.dormantUsageMultiplier);
                        }
                        if (usageMb < 0) usageMb = 0;

                        w.writeRow(List.of(
                                Long.toString(nextUsageId++),
                                Long.toString(m.memberId()),
                                t.toLocalDate().toString(),
                                Integer.toString(hour),
                                Long.toString(usageMb),
                                m.region(),
                                t.format(DateUtil.DT)
                        ));
                    }
                }
            }
        }
    }

    private boolean isActiveInMonth(MemberProfile m, YearMonth ym) {
        YearMonth signupYm = YearMonth.from(m.createdAt());
        YearMonth startYm = signupYm.isBefore(ctx.fromYm()) ? ctx.fromYm() : signupYm;
        if (ym.isBefore(startYm)) return false;
        return m.cancelYm() == null || !ym.isAfter(m.cancelYm());
    }

    private int pickActiveDays(MemberProfile m, YearMonth ym, int max) {
        Archetype a = m.archetype();

        if (a == Archetype.D6A_USAGE_DROP_DORMANT) {
            if (!ym.isBefore(ctx.toYm().minusMonths(1))) return RandomUtil.nextIntInclusive(r, 0, 2);
            if (!ym.isBefore(ctx.toYm().minusMonths(3))) return RandomUtil.nextIntInclusive(r, 2, 6);
        }
        if (a == Archetype.D6B_DISSATISFIED_CHURNED || a == Archetype.D6C_DELINQUENT_CHURNED) {
            if (m.cancelYm() != null && !ym.isBefore(m.cancelYm())) return 0;
            if (m.cancelYm() != null && !ym.isBefore(m.cancelYm().minusMonths(2))) {
                return RandomUtil.nextIntInclusive(r, 0, 4);
            }
        }

        int lo, hi;
        switch (a) {
            case L1A_UNINTERESTED_STABLE -> { lo = 5; hi = Math.min(12, max); }
            case L1B_REGULAR_LOW_USAGE -> { lo = 18; hi = Math.min(26, max); }
            case L1C_NIGHT_SIMPLE -> { lo = 12; hi = Math.min(20, max); }
            case L1D_SINGLE_INQUIRY -> { lo = 10; hi = Math.min(18, max); }
            case H2A_HEAVY_NO_COMPLAINT, H2B_HEAVY_QUALITY_SENSITIVE, H2C_HEAVY_NIGHT_FOCUS,
                 H2D_HEAVY_BILLING_DISSATISFIED, H2E_HEAVY_MULTI_SUBS -> { lo = 21; hi = Math.min(28, max); }
            case P3A_PROMO_RESPONSIVE, P3B_BILLING_TRACKER, P3C_DOWNGRADE_CONSIDERING -> { lo = 14; hi = Math.min(22, max); }
            case P3D_NEAR_DELINQUENT -> { lo = 10; hi = Math.min(18, max); }
            case C4A_CHRONIC_QUALITY_COMPLAINT, C4B_BILLING_DISPUTE, C4C_NIGHT_WEEKEND_COMPLAINT,
                 C4D_MULTI_CHANNEL_PERSISTENT, C4E_CHURN_THREAT_COMPLAINT -> { lo = 18; hi = Math.min(26, max); }
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> { lo = 16; hi = Math.min(26, max); }
            default -> { lo = 10; hi = Math.min(18, max); }
        }

        switch (m.usageSegment()) {
            case LIGHT -> {
                lo = Math.max(lo, 4);
                hi = Math.max(lo, Math.min(hi, 18));
            }
            case NORMAL -> { }
            case HEAVY -> {
                lo = Math.max(lo, 18);
                hi = Math.max(hi, 22);
            }
        }

        lo = Math.min(lo, max);
        hi = Math.min(Math.max(lo, hi), max);
        return RandomUtil.nextIntInclusive(r, lo, hi);
    }

    private long sampleUsageAmountMb(UsageSegment seg, boolean vip, Archetype a) {
        double mu;
        double sigma = 0.9;
        long mult = switch (a) {
            case L1A_UNINTERESTED_STABLE, L1B_REGULAR_LOW_USAGE, L1C_NIGHT_SIMPLE -> 1;
            case H2A_HEAVY_NO_COMPLAINT, H2B_HEAVY_QUALITY_SENSITIVE, H2C_HEAVY_NIGHT_FOCUS,
                 H2D_HEAVY_BILLING_DISSATISFIED, H2E_HEAVY_MULTI_SUBS -> 3;
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> 3;
            case D6A_USAGE_DROP_DORMANT, D6B_DISSATISFIED_CHURNED, D6C_DELINQUENT_CHURNED -> 1;
            default -> 2;
        };

        switch (seg) {
            case LIGHT -> mu = 4.5;
            case NORMAL -> mu = 6.2;
            case HEAVY -> mu = 7.6;
            default -> mu = 6.0;
        }

        if (vip) mu += 0.4;
        if (a == Archetype.H2C_HEAVY_NIGHT_FOCUS || a == Archetype.L1C_NIGHT_SIMPLE) mu += 0.15;

        double z = r.nextGaussian();
        double val = Math.exp(mu + sigma * z);
        long mb = (long) (val / 50.0) * 50;
        mb *= mult;

        long cap = switch (seg) {
            case LIGHT -> 2000;
            case NORMAL -> 20000;
            case HEAVY -> 80000;
            default -> 20000;
        };
        if (mb > cap) mb = cap;
        return Math.max(0, mb);
    }

    private int pickHour(MemberProfile m) {
        double night = m.nightBias();
        if (night > 0.68) {
            int[] hours = {22, 23, 0, 1, 2, 3};
            return hours[r.nextInt(hours.length)];
        }
        if (night > 0.42 && r.nextDouble() < 0.40) {
            int[] hours = {21, 22, 23, 0, 1};
            return hours[r.nextInt(hours.length)];
        }
        return RandomUtil.nextIntInclusive(r, 8, 23);
    }

    private boolean isVipLike(MemberProfile m) {
        return switch (m.archetype()) {
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> true;
            default -> false;
        };
    }
}
