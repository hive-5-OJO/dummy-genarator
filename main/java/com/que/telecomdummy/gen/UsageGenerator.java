package com.que.telecomdummy.gen;

import com.que.telecomdummy.model.GenerationContext;
import com.que.telecomdummy.model.MemberProfile;
import com.que.telecomdummy.model.Segment.Archetype;
import com.que.telecomdummy.model.Segment.UsageSegment;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;
import com.que.telecomdummy.util.RandomUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * data_usage 생성 (Top-down 분배 방식 + 시간 인과율 교정)
 * * * [주요 수정 사항]
 * 1. SSOT 확보: 월별 총사용량을 선계산 후 캐싱하여 타 모듈(AdviceGenerator)과 데이터 정합성 유지
 * 2. 시간 인과율 방어(Time Causality): 가입월의 경우 가입일(Day) 이전 날짜에 사용량이 찍히는 모순을 
 * 방지하기 위해 날짜 풀(Pool)을 제한하고, 가입 당일은 가입 시각(Time) 이후로 트랜잭션을 강제 클램프함.
 */
public final class UsageGenerator {
    private final GenerationContext ctx;
    private final Random r;
    private final MemberGenerator memberGen;

    private long nextUsageId = 1;

    // SSOT 상태 캐시: key = (memberId << 6) | month
    private final Map<Long, Long> monthlyUsageMapMb = new HashMap<>();

    public UsageGenerator(GenerationContext ctx, MemberGenerator memberGen) {
        this.ctx = ctx;
        this.memberGen = memberGen;
        this.r = new Random(ctx.seed() ^ 0x51A9E123L);
    }

    private static long key(long memberId, int month) {
        return (memberId << 6) ^ (month & 0x3F);
    }

    /**
     * AdviceGenerator 등 외부 모듈에서 참조하기 위한 월별 누적 사용량 반환 메서드
     */
    public double getMonthlyUsageGb(long memberId, int month) {
        long mb = monthlyUsageMapMb.getOrDefault(key(memberId, month), 0L);
        return mb / 1024.0;
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("usage");
        Files.createDirectories(outDir);

        Path file = outDir.resolve("data_usage.csv");
        try (CsvWriter w = new CsvWriter(file, List.of(
                "data_usage_id", "member_id", "usage_date", "usage_time", "usage_amount", "region", "created_at"
        ))) {
            for (int month : ctx.months()) {
                int daysInMonth = DateUtil.daysInMonthFixedFeb28(ctx.year(), month);

                for (MemberProfile m : memberGen.members()) {
                    if (m.cancelMonth() != null && month > m.cancelMonth()) continue;

                    int startMonth = (m.createdAt().getYear() == ctx.year()) ? m.createdAt().getMonthValue() : 1;
                    if (month < startMonth) continue;

                    Random rr = new Random(ctx.seed() ^ m.memberId() ^ month);

                    long totalMonthlyMb = determineMonthlyTargetMb(m, rr);
                    monthlyUsageMapMb.put(key(m.memberId(), month), totalMonthlyMb);

                    if (totalMonthlyMb == 0) continue;

                    // [시간 인과율 교정] 가입월인 경우 가입일(Day)부터 월말까지만 활성일로 지정 가능
                    int minDay = (month == startMonth) ? m.createdAt().getDayOfMonth() : 1;
                    int maxDaysToPick = daysInMonth - minDay + 1;
                    
                    int activeDays = pickActiveDays(m, month, daysInMonth, rr);
                    // 가입월에 남은 일수보다 많은 활성일이 뽑히지 않도록 제한
                    activeDays = Math.min(activeDays, maxDaysToPick);

                    if (activeDays <= 0) continue;

                    // O(N) 셔플로 안전하게 날짜 추출 (무한루프 제거)
                    List<Integer> allDays = IntStream.rangeClosed(minDay, daysInMonth).boxed().collect(Collectors.toList());
                    Collections.shuffle(allDays, rr);
                    List<Integer> activeDayList = allDays.subList(0, activeDays);
                    activeDayList.sort(Integer::compareTo);

                    double[] weights = new double[activeDays];
                    double sumWeights = 0;
                    for (int i = 0; i < activeDays; i++) {
                        weights[i] = -Math.log(1.0 - rr.nextDouble());
                        sumWeights += weights[i];
                    }

                    for (int i = 0; i < activeDays; i++) {
                        long dailyMb = (long) (totalMonthlyMb * (weights[i] / sumWeights));
                        if (dailyMb == 0) dailyMb = 1;

                        int sessions = RandomUtil.nextIntInclusive(rr, 1, 3);
                        long[] sessionAmounts = distributeEquallyWithNoise(dailyMb, sessions, rr);

                        int currentDay = activeDayList.get(i);

                        for (int s = 0; s < sessions; s++) {
                            int hour = pickHour(m, rr);
                            int minute = RandomUtil.nextIntInclusive(rr, 0, 59);
                            int second = RandomUtil.nextIntInclusive(rr, 0, 59);
                            LocalDateTime t = LocalDateTime.of(ctx.year(), month, currentDay, hour, minute, second);

                            // [시간 인과율 교정] 가입 당일(Day)의 트랜잭션은 무조건 가입 시각 이후여야 함
                            if (month == startMonth && currentDay == m.createdAt().getDayOfMonth()) {
                                if (t.isBefore(m.createdAt())) {
                                    // 랜덤 시각이 가입 시각 이전이라면, 가입 시각에서 임의의 분(minute)을 더해 미래로 밀어냄
                                    int shiftMinutes = RandomUtil.nextIntInclusive(rr, 1, 120);
                                    t = m.createdAt().plusMinutes(shiftMinutes);
                                    
                                    // 만약 더한 시간이 자정을 넘겨 다음날이 되어버리면, 당일 23:59:59로 클램프
                                    if (t.getDayOfMonth() != currentDay) {
                                        t = LocalDateTime.of(ctx.year(), month, currentDay, 23, 59, 59);
                                    }
                                }
                            }

                            w.writeRow(List.of(
                                    Long.toString(nextUsageId++),
                                    Long.toString(m.memberId()),
                                    t.toLocalDate().toString(),
                                    Integer.toString(t.getHour()),
                                    Long.toString(sessionAmounts[s]),
                                    m.region(),
                                    t.format(DateUtil.DT)
                            ));
                        }
                    }
                }
            }
        }
    }

    private long determineMonthlyTargetMb(MemberProfile m, Random rr) {
        double mu = switch (m.usageSegment()) {
            case LIGHT -> 5.5;   // ~244MB
            case NORMAL -> 7.8;  // ~2.4GB
            case HEAVY -> 9.8;   // ~18GB
            default -> 7.0;
        };

        if (isVipLike(m)) mu += 0.5;
        if (m.archetype() == Archetype.H2C_HEAVY_NIGHT_FOCUS || m.archetype() == Archetype.L1C_NIGHT_SIMPLE) mu += 0.3;

        double sigma = 0.85;
        double z = rr.nextGaussian();
        double val = Math.exp(mu + sigma * z);

        double softCap = 120_000.0;
        if (val > softCap) {
            val = softCap + Math.log(val - softCap + 1) * 10000;
        }
        return Math.max(0L, (long) val);
    }

    private long[] distributeEquallyWithNoise(long total, int pieces, Random rr) {
        long[] result = new long[pieces];
        if (pieces == 1) {
            result[0] = total;
            return result;
        }
        double[] w = new double[pieces];
        double sum = 0;
        for (int i = 0; i < pieces; i++) {
            w[i] = 0.5 + rr.nextDouble();
            sum += w[i];
        }
        long allocated = 0;
        for (int i = 0; i < pieces - 1; i++) {
            result[i] = (long) (total * (w[i] / sum));
            allocated += result[i];
        }
        result[pieces - 1] = Math.max(1, total - allocated);
        return result;
    }

    private int pickActiveDays(MemberProfile m, int month, int max, Random rr) {
        Archetype a = m.archetype();

        if (a == Archetype.D6A_USAGE_DROP_DORMANT) {
            if (month >= 10) return RandomUtil.nextIntInclusive(rr, 0, 2);
            if (month >= 8) return RandomUtil.nextIntInclusive(rr, 2, 6);
        }
        if (a == Archetype.D6B_DISSATISFIED_CHURNED || a == Archetype.D6C_DELINQUENT_CHURNED) {
            if (m.cancelMonth() != null && month >= m.cancelMonth()) return 0;
            if (m.cancelMonth() != null && month >= Math.max(1, m.cancelMonth() - 2)) {
                return RandomUtil.nextIntInclusive(rr, 0, 4);
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
            case NORMAL -> { /* keep */ }
            case HEAVY -> {
                lo = Math.max(lo, 18);
                hi = Math.max(hi, 22);
            }
        }

        lo = Math.min(lo, max);
        hi = Math.min(Math.max(lo, hi), max);
        return RandomUtil.nextIntInclusive(rr, lo, hi);
    }

    private int pickHour(MemberProfile m, Random rr) {
        Archetype a = m.archetype();
        if (a == Archetype.L1C_NIGHT_SIMPLE || a == Archetype.H2C_HEAVY_NIGHT_FOCUS) {
            int[] hours = {22, 23, 0, 1, 2, 3};
            return hours[rr.nextInt(hours.length)];
        }
        if (a == Archetype.C4C_NIGHT_WEEKEND_COMPLAINT) {
            if (rr.nextDouble() < 0.30) {
                int[] hours = {21, 22, 23, 0, 1};
                return hours[rr.nextInt(hours.length)];
            }
        }
        return RandomUtil.nextIntInclusive(rr, 8, 23);
    }

    private boolean isVipLike(MemberProfile m) {
        return switch (m.archetype()) {
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> true;
            default -> false;
        };
    }
}