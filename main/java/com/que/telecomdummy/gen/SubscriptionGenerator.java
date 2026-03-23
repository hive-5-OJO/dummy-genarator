package com.que.telecomdummy.gen;

import com.que.telecomdummy.model.*;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;
import com.que.telecomdummy.util.RandomUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * subscription_period 생성 + 월별 활성 구독 조회용 인메모리 북
 * * [시간 인과율(Timeline Causality) 교정 사항]
 * 1. 가입월 구독 역행 방지: 최초 가입월의 구독 시작 시각(st)을 '1일 00:00:00'이 아닌 
 * 'member.createdAt()'으로 하드 보정하여, 가입 이전 시간에 구독이 기록되는 논리적 모순 차단.
 * 2. 생명주기 동기화 및 DB 제약 조건 방어(CANCELLED) 유지.
 */
public final class SubscriptionGenerator {
    private final GenerationContext ctx;
    private final Random r;
    private final MasterDataGenerator masterGen;
    private final MemberGenerator memberGen;

    private final Map<Long, List<SubPeriod>> subsByMember = new HashMap<>();
    private long nextSubId = 1;

    public SubscriptionGenerator(GenerationContext ctx, MasterDataGenerator masterGen, MemberGenerator memberGen) {
        this.ctx = ctx;
        this.masterGen = masterGen;
        this.memberGen = memberGen;
        this.r = new Random(ctx.seed() ^ 0xDEADBEEFL);
    }

    public List<SubPeriod> getPeriods(long memberId) {
        return subsByMember.getOrDefault(memberId, List.of());
    }

    public List<Plan> getActiveSubscriptionPlans(long memberId, int month) {
        List<SubPeriod> periods = subsByMember.get(memberId);
        if (periods == null) return List.of();
        
        List<Plan> out = new ArrayList<>();
        for (SubPeriod p : periods) {
            if (!p.isSubscription()) continue;
            if (p.isActiveInMonth(month)) out.add(p.plan());
        }
        return out;
    }

    public List<SubPeriod> getActiveSubscriptionPeriods(long memberId, int month) {
        List<SubPeriod> periods = subsByMember.get(memberId);
        if (periods == null) return List.of();
        
        List<SubPeriod> out = new ArrayList<>();
        for (SubPeriod p : periods) {
            if (!p.isSubscription()) continue;
            if (p.isActiveInMonth(month)) out.add(p);
        }
        return out;
    }

    public int activeEndMonth(MemberProfile m) {
        return (m.cancelMonth() == null) ? 12 : m.cancelMonth();
    }

    public int activeStartMonth(MemberProfile m) {
        return (m.createdAt().getYear() == ctx.year()) ? m.createdAt().getMonthValue() : 1;
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("subscriptions");
        Files.createDirectories(outDir);

        List<SubPeriod> all = new ArrayList<>();

        MasterData md = masterGen.master();
        List<Plan> basePlans = md.plansByType().get("SUBSCRIPTION_BASE");
        List<Plan> addonPlans = md.plansByType().get("SUBSCRIPTION_ADDON");

        List<Plan> addonServicePlans = new ArrayList<>();
        List<Plan> addonDevicePlans = new ArrayList<>();
        for (Plan p : addonPlans) {
            if ("ADDON_DEVICE".equals(p.productCategory())) {
                addonDevicePlans.add(p);
            } else {
                addonServicePlans.add(p);
            }
        }

        for (MemberProfile m : memberGen.members()) {
            int startMonth = activeStartMonth(m);
            int endMonth = activeEndMonth(m);
            if (startMonth > 12) continue;

            Plan base = basePlans.get(r.nextInt(basePlans.size()));

            int addonCount = pickAddonCount(m);
            Set<Long> usedAddon = new HashSet<>();
            List<Plan> addons = new ArrayList<>();
            for (int i = 0; i < addonCount; i++) {
                Plan a;
                int guard = 0;

                double deviceProb = switch (m.archetype()) {
                    case H2E_HEAVY_MULTI_SUBS -> 0.20;
                    case V5A_LONGTERM_CORE, V5C_CARE_VIP -> 0.12;
                    default -> 0.08;
                };

                do {
                    boolean pickDevice = !addonDevicePlans.isEmpty() && (r.nextDouble() < deviceProb);
                    List<Plan> pool = pickDevice ? addonDevicePlans : addonServicePlans;
                    if (pool.isEmpty()) pool = addonPlans;
                    a = pool.get(r.nextInt(pool.size()));
                    guard++;
                } while (usedAddon.contains(a.productId()) && guard < 30);

                usedAddon.add(a.productId());
                addons.add(a);
            }

            int changeEvents = pickChangeEvents(m);
            List<Integer> changeMonths = new ArrayList<>();
            for (int i = 0; i < changeEvents; i++) {
                int cm = RandomUtil.nextIntInclusive(r, startMonth, Math.max(startMonth, endMonth));
                changeMonths.add(cm);
            }
            changeMonths.sort(Comparator.naturalOrder());

            List<SubPeriod> periods = new ArrayList<>();
            int curStart = startMonth;
            Plan curBase = base;

            for (int cm : changeMonths) {
                if (cm <= curStart) continue;
                
                // [교정 포인트] 최초 가입월(startMonth)인 경우 무조건 고객 생성 시점(createdAt) 적용
                LocalDateTime st = (curStart == startMonth) 
                        ? m.createdAt() 
                        : DateUtil.atFixed(ctx.year(), curStart, 1, 0, 0, 0);
                        
                LocalDateTime en = DateUtil.atFixed(ctx.year(), cm, 1, 0, 0, 0).minusSeconds(1);
                
                periods.add(new SubPeriod(nextSubId++, m.memberId(), curBase, st, en));

                for (Plan a : addons) {
                    periods.add(new SubPeriod(nextSubId++, m.memberId(), a, st, en));
                }

                Plan next;
                int g = 0;
                do {
                    next = basePlans.get(r.nextInt(basePlans.size()));
                    g++;
                } while (next.productId() == curBase.productId() && g < 20);

                curBase = next;
                curStart = cm;
                
                if (!addons.isEmpty() && r.nextDouble() < 0.20) {
                    addons.remove(r.nextInt(addons.size()));
                }
            }

            // [교정 포인트] 이벤트 없이 끝까지 유지되거나 마지막 이벤트 이후의 구간 처리
            LocalDateTime lastSt = (curStart == startMonth) 
                    ? m.createdAt() 
                    : DateUtil.atFixed(ctx.year(), curStart, 1, 0, 0, 0);
                    
            LocalDateTime lastEn = DateUtil.atFixed(ctx.year(), endMonth, DateUtil.daysInMonthFixedFeb28(ctx.year(), endMonth), 23, 59, 59);
            
            periods.add(new SubPeriod(nextSubId++, m.memberId(), curBase, lastSt, lastEn));

            for (Plan a : addons) {
                periods.add(new SubPeriod(nextSubId++, m.memberId(), a, lastSt, lastEn));
            }

            subsByMember.put(m.memberId(), periods);
            all.addAll(periods);
        }

        Path file = outDir.resolve("subscription_period.csv");
        try (CsvWriter w = new CsvWriter(file, List.of(
                "subscription_period_id", "product_id", "member_id", "quantity", "total_price", "started_at", "status", "end_at", "reason_code"
        ))) {
            for (SubPeriod p : all) {
                long total = p.plan().price();
                String status = (p.endAt() == null) ? "ACTIVE" : "CANCELLED";
                String endAt = (p.endAt() == null) ? "" : p.endAt().format(DateUtil.DT);
                String reason = (p.endAt() == null) ? "" : "USER_CANCEL";

                w.writeRow(List.of(
                        Long.toString(p.subscriptionPeriodId()),
                        Long.toString(p.plan().productId()),
                        Long.toString(p.memberId()),
                        "1",
                        Long.toString(total),
                        p.startedAt().format(DateUtil.DT),
                        status,
                        endAt,
                        reason
                ));
            }
        }
    }

    private int pickAddonCount(MemberProfile m) {
        double x = r.nextDouble();
        switch (m.archetype()) {
            case H2E_HEAVY_MULTI_SUBS -> {
                if (x < 0.25) return 0;
                if (x < 0.60) return 1;
                return 2;
            }
            case V5A_LONGTERM_CORE, V5C_CARE_VIP -> {
                if (x < 0.40) return 0;
                if (x < 0.80) return 1;
                return 2;
            }
            default -> {
                if (x < 0.55) return 0;
                if (x < 0.85) return 1;
                return 2;
            }
        }
    }

    private int pickChangeEvents(MemberProfile m) {
        double base = 0.10;
        base += switch (m.archetype()) {
            case P3C_DOWNGRADE_CONSIDERING, C4E_CHURN_THREAT_COMPLAINT -> 0.20;
            case P3B_BILLING_TRACKER, H2D_HEAVY_BILLING_DISSATISFIED -> 0.12;
            default -> 0.0;
        };

        if (r.nextDouble() < base) {
            return (r.nextDouble() < 0.25) ? 2 : 1;
        }
        return 0;
    }

    public static final class SubPeriod {
        private final long subscriptionPeriodId;
        private final long memberId;
        private final Plan plan;
        private final LocalDateTime startedAt;
        private final LocalDateTime endAt;

        public SubPeriod(long subscriptionPeriodId, long memberId, Plan plan, LocalDateTime startedAt, LocalDateTime endAt) {
            this.subscriptionPeriodId = subscriptionPeriodId;
            this.memberId = memberId;
            this.plan = plan;
            this.startedAt = startedAt;
            this.endAt = endAt;
        }

        public long subscriptionPeriodId() { return subscriptionPeriodId; }
        public long memberId() { return memberId; }
        public Plan plan() { return plan; }
        public LocalDateTime startedAt() { return startedAt; }
        public LocalDateTime endAt() { return endAt; }

        public boolean isSubscription() {
            return "SUBSCRIPTION".equals(plan.productType());
        }

        public boolean isActiveInMonth(int month) {
            YearMonth ym = YearMonth.of(startedAt.getYear(), month);
            LocalDateTime ms = ym.atDay(1).atTime(0, 0, 0);
            LocalDateTime me = ym.atEndOfMonth().atTime(23, 59, 59);

            boolean afterStart = !startedAt.isAfter(me);
            boolean beforeEnd = (endAt == null) || !endAt.isBefore(ms);
            return afterStart && beforeEnd;
        }
    }
}