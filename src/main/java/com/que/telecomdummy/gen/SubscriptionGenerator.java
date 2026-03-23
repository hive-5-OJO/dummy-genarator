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
 * subscription_period 생성 + 월별 활성 구독 조회용 인메모리 북.
 * - 기본: 요금제 1개 + 부가 0~2개
 * - 변경 이벤트는 낮은 확률로 0~2회 생성
 * - 해지 이후 invoice/usage/advice 없음, 해지 달까지는 포함
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

    public List<Plan> getActiveSubscriptionPlans(long memberId, YearMonth ym) {
        List<Plan> out = new ArrayList<>();
        for (SubPeriod p : subsByMember.getOrDefault(memberId, List.of())) {
            if (p.isSubscription() && p.isActiveInMonth(ym)) out.add(p.plan());
        }
        return out;
    }

    public List<SubPeriod> getActiveSubscriptionPeriods(long memberId, YearMonth ym) {
        List<SubPeriod> out = new ArrayList<>();
        for (SubPeriod p : subsByMember.getOrDefault(memberId, List.of())) {
            if (p.isSubscription() && p.isActiveInMonth(ym)) out.add(p);
        }
        return out;
    }

    public YearMonth activeEndYm(MemberProfile m) {
        return (m.cancelYm() == null || m.cancelYm().isAfter(ctx.toYm())) ? ctx.toYm() : m.cancelYm();
    }

    public YearMonth activeStartYm(MemberProfile m) {
        YearMonth signupYm = YearMonth.from(m.createdAt());
        return signupYm.isBefore(ctx.fromYm()) ? ctx.fromYm() : signupYm;
    }

    private LocalDateTime startAtForPeriod(MemberProfile m, YearMonth ym) {
        if (YearMonth.from(m.createdAt()).equals(ym)) return m.createdAt();
        return ym.atDay(1).atStartOfDay();
    }

    private LocalDateTime endOfMonth(YearMonth ym) {
        return ym.atEndOfMonth().atTime(23, 59, 59);
    }

    private int monthsBetween(YearMonth a, YearMonth b) {
        return (b.getYear() - a.getYear()) * 12 + (b.getMonthValue() - a.getMonthValue());
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
            String c = p.productCategory();
            if ("ADDON_DEVICE".equals(c)) addonDevicePlans.add(p);
            else addonServicePlans.add(p);
        }

        for (MemberProfile m : memberGen.members()) {
            YearMonth startYm = activeStartYm(m);
            YearMonth endYm = activeEndYm(m);
            if (startYm.isAfter(endYm)) continue;

            Plan base = basePlans.get(r.nextInt(basePlans.size()));
            int addonCount = pickAddonCount(m);
            Set<Long> usedAddon = new HashSet<>();
            List<Plan> addons = new ArrayList<>();
            for (int i = 0; i < addonCount; i++) {
                Plan a;
                int guard = 0;
                double deviceProb = 0.05 + (m.multiSubAffinity() * 0.20) + (m.vipFlag() ? 0.03 : 0.0);
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

            int maxPossible = Math.max(0, monthsBetween(startYm, endYm));
            int changeEvents = Math.min(pickChangeEvents(m), maxPossible);
            Set<YearMonth> changeSet = new TreeSet<>();
            int guardPick = 0;
            while (changeSet.size() < changeEvents && guardPick < 300) {
                YearMonth candidate = startYm.plusMonths(RandomUtil.nextIntInclusive(r, 1, Math.max(1, maxPossible)));
                if (!candidate.isAfter(endYm)) changeSet.add(candidate);
                guardPick++;
            }
            List<YearMonth> changeYms = new ArrayList<>(changeSet);

            List<SubPeriod> periods = new ArrayList<>();
            YearMonth curStart = startYm;
            Plan curBase = base;
            for (YearMonth changeYm : changeYms) {
                if (!changeYm.isAfter(curStart)) continue;
                LocalDateTime st = startAtForPeriod(m, curStart);
                LocalDateTime en = changeYm.atDay(1).atStartOfDay().minusSeconds(1);
                periods.add(new SubPeriod(nextSubId++, m.memberId(), curBase, st, en, "PLAN_CHANGE"));

                Plan next;
                int g = 0;
                do {
                    next = basePlans.get(r.nextInt(basePlans.size()));
                    g++;
                } while (next.productId() == curBase.productId() && g < 20);
                curBase = next;
                curStart = changeYm;
            }

            boolean canceled = m.cancelYm() != null && !m.cancelYm().isAfter(ctx.toYm());
            LocalDateTime lastSt = startAtForPeriod(m, curStart);
            LocalDateTime lastEn = canceled ? endOfMonth(endYm) : null;
            periods.add(new SubPeriod(nextSubId++, m.memberId(), curBase, lastSt, lastEn, canceled ? deriveReasonCode(curBase) : null));

            for (Plan a : addons) {
                LocalDateTime st = startAtForPeriod(m, startYm);
                LocalDateTime en = canceled ? endOfMonth(endYm) : null;
                periods.add(new SubPeriod(nextSubId++, m.memberId(), a, st, en, canceled ? deriveReasonCode(a) : null));
            }

            subsByMember.put(m.memberId(), periods);
            all.addAll(periods);
        }

        Path file = outDir.resolve("subscription_period.csv");
        try (CsvWriter w = new CsvWriter(file, List.of(
                "subscription_period_id","product_id","member_id","quantity","total_price",
                "status","started_at","end_at","reason_code"
        ))) {
            for (SubPeriod p : all) {
                long qty = 1;
                long total = p.plan().price() * qty;
                String status = p.status();
                String endAt = (p.endAt() == null) ? "" : p.endAt().format(DateUtil.DT);
                String reason = p.reasonCode() == null ? "" : p.reasonCode();
                w.writeRow(List.of(
                        Long.toString(p.subscriptionPeriodId()),
                        Long.toString(p.plan().productId()),
                        Long.toString(p.memberId()),
                        Long.toString(qty),
                        Long.toString(total),
                        status,
                        p.startedAt().format(DateUtil.DT),
                        endAt,
                        reason
                ));
            }
        }
    }

    private int pickAddonCount(MemberProfile m) {
        double x = r.nextDouble();
        double affinity = m.multiSubAffinity();

        if (affinity >= 0.65) {
            if (x < 0.22) return 0;
            if (x < 0.56) return 1;
            return 2;
        }
        if (affinity >= 0.40) {
            if (x < 0.38) return 0;
            if (x < 0.78) return 1;
            return 2;
        }
        if (x < 0.58) return 0;
        if (x < 0.86) return 1;
        return 2;
    }

    private String deriveReasonCode(Plan p) {
        String name = p.productName();
        if (name != null && (name.contains("5G") || name.contains("프리미엄") || name.contains("넷플릭스") || name.contains("워치") || name.contains("태블릿"))) {
            return "PLAN_CHANGE";
        }
        if (p.productCategory() != null && p.productCategory().contains("ADDON")) {
            return "PLAN_CHANGE";
        }
        return "USER_CANCEL";
    }

    private int pickChangeEvents(MemberProfile m) {
        double base = 0.05 + (m.retentionSensitivity() * 0.10) + (m.billingSensitivity() * 0.05) + (m.promoAffinity() * 0.03);
        base += switch (m.archetype()) {
            case P3C_DOWNGRADE_CONSIDERING, C4E_CHURN_THREAT_COMPLAINT -> 0.10;
            case P3B_BILLING_TRACKER, H2D_HEAVY_BILLING_DISSATISFIED -> 0.05;
            case V5B_VIP_PROMO_RESPONSIVE -> 0.03;
            default -> 0.0;
        };

        double x = r.nextDouble();
        if (x < base) return (r.nextDouble() < 0.22) ? 2 : 1;
        return 0;
    }

    public record SubPeriod(
            long subscriptionPeriodId,
            long memberId,
            Plan plan,
            LocalDateTime startedAt,
            LocalDateTime endAt,
            String reasonCode
    ) {
        public boolean isSubscription() { return "SUBSCRIPTION".equals(plan.productType()); }
        public boolean isActiveInMonth(YearMonth ym) {
            YearMonth st = YearMonth.from(startedAt);
            YearMonth en = (endAt == null) ? null : YearMonth.from(endAt);
            if (ym.isBefore(st)) return false;
            return en == null || !ym.isAfter(en);
        }
        public String status() { return (endAt == null) ? "ACTIVE" : "CANCLED"; }
    }
}
