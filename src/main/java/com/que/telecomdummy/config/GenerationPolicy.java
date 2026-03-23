package com.que.telecomdummy.config;

import com.que.telecomdummy.util.Args;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 워스트 케이스/특수 조건 주입 정책.
 *
 * 목표:
 * 1) "정합성"을 깨지 않으면서 분포/극단 케이스를 조절
 * 2) 코드 하드코딩 확률을 정책으로 승격
 *
 * 사용 예:
 *  - --validate true
 *  - --force-overdue-rate 0.02
 *  - --force-unpaid-in-overdue 0.30
 *  - --complaint-spike-months 7
 *  - --complaint-spike-mult 4.0
 *  - --promo-storm-months 12
 *  - --promo-attach 0.60
 *  - --promo-attach-storm 0.90
 */
public final class GenerationPolicy {

    // --- Admin/Auth knobs
    // admin.google=1 비율 (google 로그인 계정). google=1이면 password는 NULL이어야 함.
    public final double adminGoogleRate;

    // --- Lifecycle modifiers
    // status=DORMANT인 회원의 usage/advice 강도를 낮추기 위한 배수(0~1)
    public final double dormantUsageMultiplier;
    public final double dormantAdviceMultiplier;

    // --- Billing worst-case knobs (member-month)
    public final double forceOverdueMemberRate;      // 월별로 강제 연체/미납 처리할 회원 비율 (0~1)
    public final boolean forceOverdueIsTargetTotal;  // true면 forceOverdueMemberRate를 '최종 연체율 목표치'로 해석
    public final double forceUnpaidWithinOverdue;    // 강제 연체 대상 중 "미납" 비율 (0~1)
    public final double forceOverdue3pWithinOverdue; // 강제 연체 대상 중 3개월+ 연체(납부는 할 수도/안 할 수도) 비율

    // --- Advice knobs
    public final double forcedOverdueAdviceRatio;    // overdue인 달에 강제 상담 생성 확률 (기본 1.0)
    public final double baseAdviceIntensity;         // 전체 상담량 스케일 (1.0 기본, 2.0이면 전체 2배)

    // --- Outbound extra cases (member-month)
    public final double outUsageOptimizeRate;
    public final double outBillingAnomalyRate;
    public final double outAutoPayNudgeRate;
    public final double outChurnPreventionRate;

    // --- Seasonal/special conditions
    public final Set<Integer> complaintSpikeMonths;  // 특정 달 민원 폭증
    public final double complaintSpikeMultiplier;    // 폭증 달에 상담량 배수
    public final Set<Integer> promoStormMonths;      // 프로모션 시즌
    public final double promoAttachRate;             // 혜택 카테고리에서 promotion_id를 붙일 확률(일반)
    public final double promoAttachRateStorm;        // 프로모션 시즌 attach 확률

    // --- Validation helpers
    public final String billingRootName;             // categories master에서 billing root를 찾기 위한 이름(기본 "결제/청구")

    private GenerationPolicy(Builder b) {
        this.adminGoogleRate = clamp01(b.adminGoogleRate);

        this.dormantUsageMultiplier = clamp01(b.dormantUsageMultiplier);
        this.dormantAdviceMultiplier = clamp01(b.dormantAdviceMultiplier);

        this.forceOverdueMemberRate = clamp01(b.forceOverdueMemberRate);
        this.forceOverdueIsTargetTotal = b.forceOverdueIsTargetTotal;
        this.forceUnpaidWithinOverdue = clamp01(b.forceUnpaidWithinOverdue);
        this.forceOverdue3pWithinOverdue = clamp01(b.forceOverdue3pWithinOverdue);

        this.forcedOverdueAdviceRatio = clamp01(b.forcedOverdueAdviceRatio);
        this.outUsageOptimizeRate = clamp01(b.outUsageOptimizeRate);
        this.outBillingAnomalyRate = clamp01(b.outBillingAnomalyRate);
        this.outAutoPayNudgeRate = clamp01(b.outAutoPayNudgeRate);
        this.outChurnPreventionRate = clamp01(b.outChurnPreventionRate);
        this.baseAdviceIntensity = Math.max(0.1, b.baseAdviceIntensity);

        this.complaintSpikeMonths = Collections.unmodifiableSet(new HashSet<>(b.complaintSpikeMonths));
        this.complaintSpikeMultiplier = Math.max(1.0, b.complaintSpikeMultiplier);

        this.promoStormMonths = Collections.unmodifiableSet(new HashSet<>(b.promoStormMonths));
        this.promoAttachRate = clamp01(b.promoAttachRate);
        this.promoAttachRateStorm = clamp01(b.promoAttachRateStorm);

        this.billingRootName = (b.billingRootName == null || b.billingRootName.isBlank()) ? "결제/청구" : b.billingRootName.trim();
    }

    public static GenerationPolicy defaults() {
        return new Builder().build();
    }

    public String summarize() {
        return "adminGoogleRate=" + adminGoogleRate
                + ", dormantUsageMult=" + dormantUsageMultiplier
                + ", dormantAdviceMult=" + dormantAdviceMultiplier
                + ", forceOverdueRate=" + forceOverdueMemberRate                + ", forceOverdueIsTargetTotal=" + forceOverdueIsTargetTotal                + ", outUsageRate=" + outUsageOptimizeRate                + ", outAnomalyRate=" + outBillingAnomalyRate                + ", outAutoPayRate=" + outAutoPayNudgeRate                + ", outChurnRate=" + outChurnPreventionRate
                + ", forceUnpaidWithinOverdue=" + forceUnpaidWithinOverdue
                + ", forceOverdue3pWithinOverdue=" + forceOverdue3pWithinOverdue
                + ", forcedOverdueAdviceRatio=" + forcedOverdueAdviceRatio
                + ", baseAdviceIntensity=" + baseAdviceIntensity
                + ", complaintSpikeMonths=" + prettyMonths(complaintSpikeMonths)
                + ", complaintSpikeMult=" + complaintSpikeMultiplier
                + ", promoStormMonths=" + prettyMonths(promoStormMonths)
                + ", promoAttach=" + promoAttachRate
                + ", promoAttachStorm=" + promoAttachRateStorm
                + ", billingRootName=" + billingRootName;
    }

    public static GenerationPolicy fromArgs(Args a) {
        Builder b = new Builder();

        // Admin/Auth
        b.adminGoogleRate = getDoubleAny(a, List.of("--admin-google-rate", "--adminGoogleRate"), b.adminGoogleRate);

        // Lifecycle modifiers
        b.dormantUsageMultiplier = getDoubleAny(a, List.of("--dormant-usage-mult", "--dormantUsageMultiplier"), b.dormantUsageMultiplier);
        b.dormantAdviceMultiplier = getDoubleAny(a, List.of("--dormant-advice-mult", "--dormantAdviceMultiplier"), b.dormantAdviceMultiplier);

        b.forceOverdueMemberRate = getDoubleAny(a, List.of("--force-overdue-rate", "--forceOverdueRate"), b.forceOverdueMemberRate);
        b.forceOverdueIsTargetTotal = getBoolAny(a, List.of("--force-overdue-target", "--forceOverdueIsTargetTotal"), b.forceOverdueIsTargetTotal);
        b.outUsageOptimizeRate = getDoubleAny(a, List.of("--out-usage-rate", "--outUsageRate"), b.outUsageOptimizeRate);
        b.outBillingAnomalyRate = getDoubleAny(a, List.of("--out-anomaly-rate", "--outAnomalyRate"), b.outBillingAnomalyRate);
        b.outAutoPayNudgeRate = getDoubleAny(a, List.of("--out-autopay-rate", "--outAutoPayRate"), b.outAutoPayNudgeRate);
        b.outChurnPreventionRate = getDoubleAny(a, List.of("--out-churn-rate", "--outChurnRate"), b.outChurnPreventionRate);
        b.forceUnpaidWithinOverdue = getDoubleAny(a, List.of("--force-unpaid-in-overdue", "--forceUnpaidWithinOverdue"), b.forceUnpaidWithinOverdue);
        b.forceOverdue3pWithinOverdue = getDoubleAny(a, List.of("--force-overdue3p-in-overdue", "--forceOverdue3pWithinOverdue"), b.forceOverdue3pWithinOverdue);

        b.forcedOverdueAdviceRatio = getDoubleAny(a, List.of("--forced-overdue-advice", "--forcedOverdueAdviceRatio"), b.forcedOverdueAdviceRatio);
        b.baseAdviceIntensity = getDoubleAny(a, List.of("--advice-intensity", "--baseAdviceIntensity"), b.baseAdviceIntensity);

        b.complaintSpikeMonths = parseMonthsSet(a.getStringAny(List.of("--complaint-spike-months", "--complaintSpikeMonths"), ""));
        b.complaintSpikeMultiplier = getDoubleAny(a, List.of("--complaint-spike-mult", "--complaintSpikeMult"), b.complaintSpikeMultiplier);

        b.promoStormMonths = parseMonthsSet(a.getStringAny(List.of("--promo-storm-months", "--promoStormMonths"), ""));
        b.promoAttachRate = getDoubleAny(a, List.of("--promo-attach", "--promoAttach"), b.promoAttachRate);
        b.promoAttachRateStorm = getDoubleAny(a, List.of("--promo-attach-storm", "--promoAttachStorm"), b.promoAttachRateStorm);

        b.billingRootName = a.getStringAny(List.of("--billing-root-name", "--billingRootName"), b.billingRootName);

        return b.build();
    }

    private static boolean getBoolAny(Args a, List<String> keys, boolean def) {
        String s = a.getStringAny(keys, null);
        if (s == null || s.isBlank()) return def;
        String t = s.trim();
        return "true".equalsIgnoreCase(t) || "1".equals(t) || "yes".equalsIgnoreCase(t);
    }

    private static double getDoubleAny(Args a, List<String> keys, double def) {
        String s = a.getStringAny(keys, null);
        if (s == null || s.isBlank()) return def;
        try { return Double.parseDouble(s.trim()); }
        catch (Exception e) { return def; }
    }

    /**
     * "7", "7,12", "1-3,8,10-12" 허용
     */
    private static Set<Integer> parseMonthsSet(String spec) {
        if (spec == null) return Set.of();
        String s = spec.trim();
        if (s.isEmpty()) return Set.of();
        Set<Integer> out = new HashSet<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("-")) {
                String[] ab = p.split("-", 2);
                int a = parseIntSafe(ab[0].trim(), -1);
                int b = parseIntSafe(ab[1].trim(), -1);
                if (a < 1 || b < 1) continue;
                int lo = Math.min(a, b);
                int hi = Math.max(a, b);
                for (int m = lo; m <= hi; m++) if (m >= 1 && m <= 12) out.add(m);
            } else {
                int m = parseIntSafe(p, -1);
                if (m >= 1 && m <= 12) out.add(m);
            }
        }
        return out;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String prettyMonths(Set<Integer> months) {
        if (months == null || months.isEmpty()) return "[]";
        return months.stream().sorted().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
    }

    public static final class Builder {
        double adminGoogleRate = 0.20;

        double dormantUsageMultiplier = 0.25;
        double dormantAdviceMultiplier = 0.35;

        double forceOverdueMemberRate = 0.0;
        boolean forceOverdueIsTargetTotal = false;
        double forceUnpaidWithinOverdue = 0.25;
        double forceOverdue3pWithinOverdue = 0.15;

        double forcedOverdueAdviceRatio = 1.0;
        double baseAdviceIntensity = 1.0;

        double outUsageOptimizeRate = 0.03;
        double outBillingAnomalyRate = 0.01;
        double outAutoPayNudgeRate = 0.02;
        double outChurnPreventionRate = 0.015;


        Set<Integer> complaintSpikeMonths = Set.of();
        double complaintSpikeMultiplier = 4.0;

        Set<Integer> promoStormMonths = Set.of();
        double promoAttachRate = 0.60;
        double promoAttachRateStorm = 0.90;

        String billingRootName = "결제/청구";

        public GenerationPolicy build() { return new GenerationPolicy(this); }
    }
}
