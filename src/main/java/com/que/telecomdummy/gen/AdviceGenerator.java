package com.que.telecomdummy.gen;

import com.que.telecomdummy.config.GenerationPolicy;
import com.que.telecomdummy.model.*;
import com.que.telecomdummy.model.Segment.Archetype;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;
import com.que.telecomdummy.util.RandomUtil;
import com.que.telecomdummy.util.WeightedPicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * advice 생성(월별) - 정합성 강화판
 *
 * 정합성 불변조건:
 * 1) start_at <= end_at == created_at  (기록=상담 종료 시점으로 단순화)
 * 2) billed/paid_at/overdue 같은 billing 사실은 "결제/청구" 계열 카테고리에서만 표현
 * 3) overdue_amount > 0 인 (member,month)은 강제 상담 생성(확률은 policy로 제어)
 *
 * 특수조건/워스트케이스:
 * - complaintSpikeMonths: 해당 달 상담량 배수 적용
 * - promoStormMonths: 혜택 상담에서 promotion_id 부착 확률 증가
 * - baseAdviceIntensity: 전체 상담량 스케일
 */
public final class AdviceGenerator {
    private final GenerationContext ctx;
    private final Random r;
    private final MasterDataGenerator masterGen;
    private final MemberGenerator memberGen;
    private final SubscriptionGenerator subGen;
    private final BillingGenerator billingGen;
    private final GenerationPolicy policy;

    private long nextAdviceId = 1;

    // ---------------------------------------------------------------------
    // ✅ 담당자 배정 룰 (ERD 고정: direction + promotion_id만 사용)
    // - "상담은 CS만": IN은 무조건 CS
    // - "프로모션/캠페인성 아웃바운드": OUT + promotion_id 존재 -> Marketing 가능(있으면), 없으면 CS
    // - 그 외 OUT(프로모션 없음)도 CS
    // - Admin(role=Admin) 제외
    // - status ACTIVE만 배정
    // ---------------------------------------------------------------------
    private static final String ROLE_CS = "CS";
    private static final String ROLE_MARKETING = "Marketing";
    private static final String ROLE_ADMIN = "Admin";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final class AssigneePool {
        final List<AdminUser> cs;
        final List<AdminUser> mkt;

        private AssigneePool(List<AdminUser> cs, List<AdminUser> mkt) {
            this.cs = cs;
            this.mkt = mkt;
        }

        static AssigneePool from(List<AdminUser> admins) {
            if (admins == null || admins.isEmpty()) {
                throw new IllegalStateException("admins is empty: cannot assign advice owner.");
            }

            List<AdminUser> cs = new ArrayList<>();
            List<AdminUser> mkt = new ArrayList<>();

            for (AdminUser a : admins) {
                if (a == null) continue;

                // status ACTIVE만
                String st = a.status();
                if (st != null && !STATUS_ACTIVE.equalsIgnoreCase(st)) continue;

                String role = a.role();
                if (role == null) continue;

                // Admin은 배정 금지
                if (ROLE_ADMIN.equalsIgnoreCase(role)) continue;

                if (ROLE_CS.equalsIgnoreCase(role)) cs.add(a);
                else if (ROLE_MARKETING.equalsIgnoreCase(role)) mkt.add(a);
            }

            // IN 상담은 무조건 CS라서 CS가 없으면 생성 자체가 불가능
            if (cs.isEmpty()) {
                throw new IllegalStateException("No ACTIVE CS users exist. Cannot assign 상담 records.");
            }

            return new AssigneePool(Collections.unmodifiableList(cs), Collections.unmodifiableList(mkt));
        }
    }

    private AdminUser pickAssignee(AssigneePool pool, String direction, String promotionId) {
        boolean isOut = "OUT".equalsIgnoreCase(direction);
        boolean hasPromo = promotionId != null && !promotionId.isBlank();

        // IN은 "상담"이므로 CS 고정
        if (!isOut) {
            return pool.cs.get(r.nextInt(pool.cs.size()));
        }

        // OUT인데 프로모션이 붙은 경우에만 Marketing 허용
        if (hasPromo && !pool.mkt.isEmpty()) {
            return pool.mkt.get(r.nextInt(pool.mkt.size()));
        }

        // OUT이지만 프로모션 없으면 CS
        return pool.cs.get(r.nextInt(pool.cs.size()));
    }

    public AdviceGenerator(GenerationContext ctx,
                           MasterDataGenerator masterGen,
                           MemberGenerator memberGen,
                           SubscriptionGenerator subGen,
                           BillingGenerator billingGen,
                           GenerationPolicy policy) {
        this.ctx = ctx;
        this.masterGen = masterGen;
        this.memberGen = memberGen;
        this.subGen = subGen;
        this.billingGen = billingGen;
        this.policy = (policy == null) ? GenerationPolicy.defaults() : policy;
        this.r = new Random(ctx.seed() ^ 0xAD1CE12345L);
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("advice");
        Files.createDirectories(outDir);

        MasterData md = masterGen.master();
        List<AdminUser> admins = md.admins();

        // ✅ pool 구성(여기서 Admin 제외/ACTIVE 필터 적용)
        AssigneePool assigneePool = AssigneePool.from(admins);

        List<Category> categories = md.categories();
        List<Promotion> promotions = md.promotions();

        // category tree -> leaf indices
        Map<Integer, Category> catById = new HashMap<>();
        Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
        for (Category c : categories) {
            catById.put(c.categoryId(), c);
            if (c.parentId() != null) {
                childrenByParent.computeIfAbsent(c.parentId(), k -> new ArrayList<>()).add(c.categoryId());
            }
        }

        Map<String, Integer> rootIdByName = new HashMap<>();
        for (Category c : categories) {
            if (c.parentId() == null) rootIdByName.put(c.categoryName(), c.categoryId());
        }

        int billingRoot = rootIdByName.getOrDefault(policy.billingRootName, 1);
        int qualityRoot = rootIdByName.getOrDefault("품질/장애", 2);
        int planRoot = rootIdByName.getOrDefault("요금제/상품", 3);
        int benefitRoot = rootIdByName.getOrDefault("혜택/프로모션", 4);
        int terminateRoot = rootIdByName.getOrDefault("가입/해지/변경", 5);
        int etcRoot = rootIdByName.getOrDefault("기타", 6);

        Map<Integer, List<Integer>> leafByRoot = new HashMap<>();
        for (int root : List.of(billingRoot, qualityRoot, planRoot, benefitRoot, terminateRoot, etcRoot)) {
            leafByRoot.put(root, collectLeafs(root, childrenByParent));
            if (leafByRoot.get(root).isEmpty()) leafByRoot.get(root).add(root);
        }

        int billingOverdueLeaf = findLeafByKeyword(leafByRoot.get(billingRoot), catById,
                List.of("연체", "미납", "납부"));
        int billingInvoiceLeaf = findLeafByKeyword(leafByRoot.get(billingRoot), catById,
                List.of("청구", "요금", "청구서"));
        int benefitPromoLeaf = findLeafByKeyword(leafByRoot.get(benefitRoot), catById,
                List.of("프로모션", "할인", "쿠폰", "멤버십", "제휴"));

        /* direction은 archetype별로 다르게 만든다 (IN/OUT 비율) */
        /* channel은 archetype별로 다르게 만든다 */

        for (YearMonth ym : ctx.periods()) {
            int month = ym.getMonthValue();
            Path path = outDir.resolve("advice_" + DateUtil.ym(ym) + ".csv");

            boolean isComplaintSpike = policy.complaintSpikeMonths.contains(month);
            boolean isPromoStorm = policy.promoStormMonths.contains(month);

            List<Promotion> activePromos = filterActivePromotions(promotions, ym);

            try (CsvWriter w = new CsvWriter(path, List.of(
                    "advice_id","member_id","admin_id","category_id","promotion_id","direction","channel",
                    "advice_content","start_at","end_at","created_at","satisfaction_score"
            ))) {

                for (MemberProfile m : memberGen.members()) {
                    if (!isActiveMonth(m, ym)) continue;

                    Optional<BillingGenerator.InvoiceState> invOpt = billingGen.getInvoiceState(m.memberId(), ym);

                    // (1) overdue/unpaid면 billing 상담을 최소 1건 "사실 기반"으로 강제 생성 (policy로 비율 조절)
                    if (invOpt.isPresent() && invOpt.get().overdueAmount() > 0 && r.nextDouble() < policy.forcedOverdueAdviceRatio) {
                        BillingGenerator.InvoiceState inv = invOpt.get();

                        String forcedDirection = (inv.paidAt() == null) ? "OUT" : "IN";
                        String forcedChannel = (inv.paidAt() == null) ? pickFrom(List.of("SMS","CALL")) : pickFrom(List.of("CALL","APP"));
                        int forcedCategoryId = (billingOverdueLeaf != -1) ? billingOverdueLeaf : billingRoot;

                        AdviceTimes t = makeAdviceTimesForMember(m, ym);

                        List<Plan> activeSubs = subGen.getActiveSubscriptionPlans(m.memberId(), ym);
                        String content = buildAdviceContent(
                                true,
                                forcedCategoryId,
                                catById.get(forcedCategoryId),
                                null,
                                activeSubs,
                                inv,
                                forcedChannel
                        );

                        // ✅ 프로모션 없으므로 promotionId=""로 배정(OUT이더라도 CS)
                        AdminUser admin0 = pickAssignee(assigneePool, forcedDirection, "");

                        w.writeRow(List.of(
                                Long.toString(nextAdviceId++),
                                Long.toString(m.memberId()),
                                Long.toString(admin0.adminId()),
                                Integer.toString(forcedCategoryId),
                                "",
                                forcedDirection,
                                forcedChannel,
                                content,
                                t.startAt.format(DateUtil.DT),
                                t.endAt.format(DateUtil.DT),
                                t.createdAt.format(DateUtil.DT),
                                (inv.paidAt() == null && r.nextDouble() < 0.85) ? "" : Integer.toString(RandomUtil.nextIntInclusive(r, 1, 10))
                        ));

                        // unpaid면 follow-up 1건 추가(확률)
                        if (inv.paidAt() == null && r.nextDouble() < 0.35) {
                            AdviceTimes t2 = makeAdviceTimesForMember(m, ym);

                            // ✅ OUT이지만 promo 없음 -> CS
                            AdminUser admin1 = pickAssignee(assigneePool, "OUT", "");

                            w.writeRow(List.of(
                                    Long.toString(nextAdviceId++),
                                    Long.toString(m.memberId()),
                                    Long.toString(admin1.adminId()),
                                    Integer.toString(forcedCategoryId),
                                    "",
                                    "OUT",
                                    pickFrom(List.of("SMS","CALL")),
                                    "미납 재안내: base_month=" + inv.baseMonth() + ", overdue=" + inv.overdueAmount() + ", 납부 방법/기한 안내",
                                    t2.startAt.format(DateUtil.DT),
                                    t2.endAt.format(DateUtil.DT),
                                    t2.createdAt.format(DateUtil.DT),
                                    ""
                            ));
                        }
                    }

                    // (1.5) OUT 추가 케이스: 사용량/요금제 최적화, 청구 이상, 자동납부 유도, 저활성 이탈 예방
                    // - 원칙: billingRoot 계열에서만 billed/paid 같은 청구 사실을 노출한다.
                    if (invOpt.isPresent()) {
                        BillingGenerator.InvoiceState inv = invOpt.get();

                        // (A) 청구 급증/이상 과금 확인 OUT (전월 대비 1.5배+)
                        if (policy.outBillingAnomalyRate > 0 && !ym.equals(ctx.fromYm())) {
                            Optional<BillingGenerator.InvoiceState> prevOpt = billingGen.getInvoiceState(m.memberId(), ym.minusMonths(1));
                            if (prevOpt.isPresent()) {
                                long prevBilled = prevOpt.get().billedAmount();
                                if (prevBilled > 0 && inv.billedAmount() >= (long)Math.ceil(prevBilled * 1.5)
                                        && r.nextDouble() < policy.outBillingAnomalyRate) {
                                    AdviceTimes tA = makeAdviceTimesForMember(m, ym);
                                    String chA = pickFrom(List.of("CALL","SMS"));
                                    String contentA = "청구 급증 확인 안내: prev_billed=" + prevBilled + ", billed=" + inv.billedAmount()
                                            + ", base_month=" + inv.baseMonth() + " (이상 과금/변동 사유 확인) (" + chA + ")";

                                    // ✅ promo 없음 -> CS
                                    AdminUser adminA = pickAssignee(assigneePool, "OUT", "");

                                    w.writeRow(List.of(
                                            Long.toString(nextAdviceId++),
                                            Long.toString(m.memberId()),
                                            Long.toString(adminA.adminId()),
                                            Integer.toString(billingRoot),
                                            "",
                                            "OUT",
                                            chA,
                                            contentA,
                                            tA.startAt.format(DateUtil.DT),
                                            tA.endAt.format(DateUtil.DT),
                                            tA.createdAt.format(DateUtil.DT),
                                            RandomUtil.maybeScore(r)
                                    ));
                                }
                            }
                        }

                        // (B) 자동납부/결제수단 변경 유도 OUT (납부가 due_date 이후인 경우)
                        if (policy.outAutoPayNudgeRate > 0 && inv.paidAt() != null && inv.paidAt().toLocalDate().isAfter(inv.dueDate())
                                && r.nextDouble() < policy.outAutoPayNudgeRate) {
                            AdviceTimes tB = makeAdviceTimesForMember(m, ym);
                            String chB = pickFrom(List.of("CALL","APP","SMS"));
                            String contentB = "자동납부/결제수단 변경 안내: 납부지연 예방, 다음 달부터 자동이체/카드등록 권유"
                                    + " (base_month=" + inv.baseMonth() + ") (" + chB + ")";

                            // ✅ promo 없음 -> CS
                            AdminUser adminB = pickAssignee(assigneePool, "OUT", "");

                            w.writeRow(List.of(
                                    Long.toString(nextAdviceId++),
                                    Long.toString(m.memberId()),
                                    Long.toString(adminB.adminId()),
                                    Integer.toString(billingRoot),
                                    "",
                                    "OUT",
                                    chB,
                                    contentB,
                                    tB.startAt.format(DateUtil.DT),
                                    tB.endAt.format(DateUtil.DT),
                                    tB.createdAt.format(DateUtil.DT),
                                    RandomUtil.maybeScore(r)
                            ));
                        }

                        // (C) 사용량/요금제 최적화 OUT (업셀/다운셀)
                        if (policy.outUsageOptimizeRate > 0) {
                            List<Plan> subsNow = subGen.getActiveSubscriptionPlans(m.memberId(), ym);
                            Plan rep = subsNow.isEmpty() ? null : subsNow.get(0);
                            long price = (rep == null) ? 0 : rep.price();
                            double allowanceGb = estimateAllowanceGb(price);
                            double usageGb = synthUsageGb(ctx.seed(), m.memberId(), ym, allowanceGb);

                            boolean wantsUpsell = allowanceGb > 0 && usageGb >= allowanceGb * 1.2;
                            boolean wantsDownsell = price >= 55000 && allowanceGb > 0 && usageGb <= allowanceGb * 0.25;

                            if ((wantsUpsell || wantsDownsell) && r.nextDouble() < policy.outUsageOptimizeRate) {
                                AdviceTimes tC = makeAdviceTimesForMember(m, ym);
                                String chC = pickFrom(List.of("CALL","APP"));
                                String repName = (rep == null) ? "" : rep.productName();
                                String contentC;
                                if (wantsUpsell) {
                                    contentC = "요금제 추천(업셀): 최근 사용량=" + String.format(Locale.US, "%.1f", usageGb)
                                            + "GB, 제공량≈" + String.format(Locale.US, "%.0f", allowanceGb)
                                            + "GB (현재=" + repName + ") -> 상위/무제한 요금제 안내 (" + chC + ")";
                                } else {
                                    contentC = "요금제 최적화(다운셀): 최근 사용량=" + String.format(Locale.US, "%.1f", usageGb)
                                            + "GB, 제공량≈" + String.format(Locale.US, "%.0f", allowanceGb)
                                            + "GB (현재=" + repName + ") -> 하위/절약 요금제 안내 (" + chC + ")";
                                }

                                // ✅ promo 없음 -> CS
                                AdminUser adminC = pickAssignee(assigneePool, "OUT", "");

                                w.writeRow(List.of(
                                        Long.toString(nextAdviceId++),
                                        Long.toString(m.memberId()),
                                        Long.toString(adminC.adminId()),
                                        Integer.toString(planRoot),
                                        "",
                                        "OUT",
                                        chC,
                                        contentC,
                                        tC.startAt.format(DateUtil.DT),
                                        tC.endAt.format(DateUtil.DT),
                                        tC.createdAt.format(DateUtil.DT),
                                        RandomUtil.maybeScore(r)
                                ));
                            }

                            // (D) 저활성 이탈 예방 OUT (저가 요금제 + 사용량 극저)
                            if (policy.outChurnPreventionRate > 0 && price > 0 && price <= 33000 && usageGb <= Math.max(0.8, allowanceGb * 0.2)
                                    && r.nextDouble() < policy.outChurnPreventionRate) {
                                AdviceTimes tD = makeAdviceTimesForMember(m, ym);
                                String chD = pickFrom(List.of("CALL","SMS"));
                                String repName = (rep == null) ? "" : rep.productName();
                                String contentD = "저활성 케어 안내: 최근 사용량 저하 감지("
                                        + String.format(Locale.US, "%.1f", usageGb) + "GB)"
                                        + (repName.isEmpty() ? "" : (" (현재=" + repName + ")"))
                                        + " -> 앱/서비스 이용방법, 혜택/부가서비스 안내 (" + chD + ")";

                                // ✅ promo 없음 -> CS
                                AdminUser adminD = pickAssignee(assigneePool, "OUT", "");

                                w.writeRow(List.of(
                                        Long.toString(nextAdviceId++),
                                        Long.toString(m.memberId()),
                                        Long.toString(adminD.adminId()),
                                        Integer.toString(planRoot),
                                        "",
                                        "OUT",
                                        chD,
                                        contentD,
                                        tD.startAt.format(DateUtil.DT),
                                        tD.endAt.format(DateUtil.DT),
                                        tD.createdAt.format(DateUtil.DT),
                                        RandomUtil.maybeScore(r)
                                ));
                            }
                        }
                    }

                    // (2) 일반 상담 생성(월별 상담수 모델) - 정책/스파이크 반영
                    int count = pickAdviceCountPerMonth(m, invOpt, ym, isComplaintSpike);

                    for (int i = 0; i < count; i++) {

                        int root = pickCategoryRootId(m, invOpt, billingRoot, qualityRoot, planRoot, benefitRoot, terminateRoot, etcRoot, isPromoStorm);
                        int categoryId = pickFrom(leafByRoot.get(root));

                        // billing이지만 overdue 없으면 "청구서 문의" leaf로 유도
                        if (root == billingRoot && invOpt.isPresent() && invOpt.get().overdueAmount() == 0 && billingInvoiceLeaf != -1) {
                            categoryId = billingInvoiceLeaf;
                        }

                        Promotion promo = null;
                        String promotionId = "";
                        if (root == benefitRoot && !activePromos.isEmpty()) {
                            double attach = promoAttachRate(m, isPromoStorm);
                            if (r.nextDouble() < attach) {
                                promo = activePromos.get(r.nextInt(activePromos.size()));
                                promotionId = promo.promotionId();
                                if (benefitPromoLeaf != -1) categoryId = benefitPromoLeaf;
                            }
                        }

                        String direction = pickDirection(m);
                        String channel = pickChannel(m, direction);

                        // ✅ 핵심: 룰 기반 담당자 배정
                        AdminUser admin = pickAssignee(assigneePool, direction, promotionId);

                        AdviceTimes t = makeAdviceTimesForMember(m, ym);

                        List<Plan> activeSubs = subGen.getActiveSubscriptionPlans(m.memberId(), ym);
                        String content = buildAdviceContent(
                                root == billingRoot,
                                categoryId,
                                catById.get(categoryId),
                                promo,
                                activeSubs,
                                invOpt.orElse(null),
                                channel
                        );

                        String satisfaction = pickSatisfaction(m, direction, root, invOpt);

                        w.writeRow(List.of(
                                Long.toString(nextAdviceId++),
                                Long.toString(m.memberId()),
                                Long.toString(admin.adminId()),
                                Integer.toString(categoryId),
                                promotionId,
                                direction,
                                channel,
                                content,
                                t.startAt.format(DateUtil.DT),
                                t.endAt.format(DateUtil.DT),
                                t.createdAt.format(DateUtil.DT),
                                satisfaction
                        ));
                        
                    }
                }
            }
        }
    }

    // AdviceGenerator 내부에 추가

    private double promoAttachRate(MemberProfile m, boolean isPromoStorm) {
        double base = isPromoStorm ? policy.promoAttachRateStorm : policy.promoAttachRate;
        double rate = base + (m.promoAffinity() - 0.50) * 0.45 + (m.outboundAffinity() - 0.50) * 0.08;
        if (m.cancelYm() != null) rate -= 0.04;
        if ("DORMANT".equalsIgnoreCase(m.status())) rate -= 0.10;
        return Math.max(0.02, Math.min(0.98, rate));
    }

    private String pickDirection(MemberProfile m) {
        double outRate = 0.08 + (m.outboundAffinity() * 0.55) + (m.promoAffinity() * 0.10) + (m.retentionSensitivity() * 0.05);
        if (m.complaintFlag()) outRate -= 0.08;
        if (m.cancelYm() != null) outRate += 0.04;
        if ("DORMANT".equalsIgnoreCase(m.status())) outRate += 0.06;
        return (r.nextDouble() < Math.max(0.03, Math.min(0.85, outRate))) ? "OUT" : "IN";
    }

    private String pickChannel(MemberProfile m, String direction) {
        int call = 30;
        int app = 30;
        int sms = 12;

        call += (int)Math.round(m.billingSensitivity() * 12 + m.qualitySensitivity() * 10);
        app += (int)Math.round((1.0 - m.billingSensitivity()) * 10 + m.promoAffinity() * 10);
        sms += (int)Math.round(m.outboundAffinity() * 18 + m.promoAffinity() * 8);

        if ("OUT".equals(direction)) {
            sms += 8;
            call += 6;
        } else {
            app += 6;
        }

        if (m.nightBias() > 0.55) app += 6;
        if (m.complaintFlag()) call += 6;

        return weightedPick(new String[]{"CALL","APP","SMS"}, new int[]{Math.max(1, call), Math.max(1, app), Math.max(1, sms)});
    }

    private double baseLambdaByMember(MemberProfile m) {
        double base = switch (m.archetype()) {
            case L1A_UNINTERESTED_STABLE -> 0.05;
            case L1B_REGULAR_LOW_USAGE -> 0.08;
            case L1C_NIGHT_SIMPLE -> 0.06;
            case L1D_SINGLE_INQUIRY -> 0.09;
            case H2A_HEAVY_NO_COMPLAINT -> 0.08;
            case H2B_HEAVY_QUALITY_SENSITIVE -> 0.22;
            case H2C_HEAVY_NIGHT_FOCUS -> 0.10;
            case H2D_HEAVY_BILLING_DISSATISFIED -> 0.22;
            case H2E_HEAVY_MULTI_SUBS -> 0.12;
            case P3A_PROMO_RESPONSIVE -> 0.20;
            case P3B_BILLING_TRACKER -> 0.30;
            case P3C_DOWNGRADE_CONSIDERING -> 0.18;
            case P3D_NEAR_DELINQUENT -> 0.22;
            case C4A_CHRONIC_QUALITY_COMPLAINT -> 0.45;
            case C4B_BILLING_DISPUTE -> 0.42;
            case C4C_NIGHT_WEEKEND_COMPLAINT -> 0.42;
            case C4D_MULTI_CHANNEL_PERSISTENT -> 0.56;
            case C4E_CHURN_THREAT_COMPLAINT -> 0.38;
            case V5A_LONGTERM_CORE -> 0.07;
            case V5B_VIP_PROMO_RESPONSIVE -> 0.14;
            case V5C_CARE_VIP -> 0.22;
            case V5D_VIP_QUALITY_SENSITIVE -> 0.20;
            case D6A_USAGE_DROP_DORMANT -> 0.03;
            case D6B_DISSATISFIED_CHURNED -> 0.26;
            case D6C_DELINQUENT_CHURNED -> 0.24;
            default -> 0.12;
        };

        base += m.contactPropensity() * 0.70;
        base += m.billingSensitivity() * 0.16;
        base += m.qualitySensitivity() * 0.16;
        base += m.retentionSensitivity() * 0.08;
        base -= (1.0 - m.contactPropensity()) * 0.05;
        return Math.max(0.01, base);
    }


    private int scoreToWeight(double score) {
        return Math.max(1, (int)Math.round(Math.max(0.01, score) * 100.0));
    }

    private double noise() {
        return r.nextGaussian() * 0.08;
    }

    private <T> T weightedPick(T[] items, int[] weights) {
        int sum = 0;
        for (int w : weights) sum += Math.max(0, w);
        if (sum <= 0) return items[0];

        int x = r.nextInt(sum); // AdviceGenerator의 Random r 사용 가정
        int acc = 0;
        for (int i = 0; i < items.length; i++) {
            acc += Math.max(0, weights[i]);
            if (x < acc) return items[i];
        }
        return items[items.length - 1];
    }

    private boolean isActiveMonth(MemberProfile m, YearMonth ym) {
        YearMonth signupYm = YearMonth.from(m.createdAt());
        YearMonth startYm = signupYm.isBefore(ctx.fromYm()) ? ctx.fromYm() : signupYm.plusMonths(1);
        if (ym.isBefore(startYm)) return false;
        return m.cancelYm() == null || !ym.isAfter(m.cancelYm());
    }

    private int pickAdviceCountPerMonth(MemberProfile m, Optional<BillingGenerator.InvoiceState> invOpt, YearMonth ym, boolean isComplaintSpike) {
        // 기존 포아송 기반을 유지하되, intensity / spike를 곱해서 조절 가능하게 함
        double lambda = baseLambdaByMember(m);

        // 기존 플래그는 노이즈/호환용으로만 가산
        switch (m.usageSegment()) {
            case LIGHT -> lambda *= 0.85;
            case NORMAL -> { /* keep */ }
            case HEAVY -> lambda *= 1.10;
        }
        if (m.complaintFlag()) lambda += 0.20;
        if (m.vipFlag()) lambda += 0.02;
        if (invOpt.isPresent() && invOpt.get().overdueAmount() > 0) lambda += 0.18;
        if (invOpt.isPresent() && invOpt.get().paidAt() == null && invOpt.get().overdueAmount() > 0) lambda += 0.10;

        lambda *= policy.baseAdviceIntensity;
        if (isComplaintSpike) lambda *= policy.complaintSpikeMultiplier;

        // status 기반 추가 감쇠: 휴면 회원은 상담량이 줄어드는 게 자연스럽다.
        // (연체/미납 강제 상담은 별도 로직으로 보장)
        if ("DORMANT".equalsIgnoreCase(m.status())) {
            lambda *= policy.dormantAdviceMultiplier;
        }

        int k = poisson(lambda);
        return Math.min(k, 12);
    }

    private int poisson(double lambda) {
        if (lambda <= 0) return 0;
        double L = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do { k++; p *= r.nextDouble(); } while (p > L);
        return k - 1;
    }

    private int pickCategoryRootId(MemberProfile m,
                                   Optional<BillingGenerator.InvoiceState> invOpt,
                                   int billing, int quality, int plan, int benefit, int terminate, int etc,
                                   boolean isPromoStorm) {

        double overdue = (invOpt.isPresent() && invOpt.get().overdueAmount() > 0) ? 1.0 : 0.0;
        double unpaid = (invOpt.isPresent() && invOpt.get().overdueAmount() > 0 && invOpt.get().paidAt() == null) ? 1.0 : 0.0;
        double billScore = 0.18 + m.billingSensitivity() * 0.95 + overdue * 0.60 + unpaid * 0.25 + noise();
        double qualityScore = 0.16 + m.qualitySensitivity() * 0.95 + (m.complaintFlag() ? 0.18 : 0.0) + noise();
        double benefitScore = 0.14 + m.promoAffinity() * 0.95 + (isPromoStorm ? 0.25 : 0.0) + (m.vipFlag() ? 0.08 : 0.0) + noise();
        double terminateScore = 0.10 + m.retentionSensitivity() * 0.90 + (m.cancelYm() != null ? 0.16 : 0.0) + noise();
        double planScore = 0.18 + (m.multiSubAffinity() * 0.20) + ((1.0 - m.billingSensitivity()) * 0.12) + noise();
        double etcScore = 0.10 + (1.0 - m.contactPropensity()) * 0.12 + noise();

        return weightedPick(
                new Integer[]{billing, quality, plan, benefit, terminate, etc},
                new int[]{
                        scoreToWeight(billScore),
                        scoreToWeight(qualityScore),
                        scoreToWeight(planScore),
                        scoreToWeight(benefitScore),
                        scoreToWeight(terminateScore),
                        scoreToWeight(etcScore)
                }
        );
    }

    private String pickSatisfaction(MemberProfile m,
                                    String direction,
                                    int root,
                                    Optional<BillingGenerator.InvoiceState> invOpt) {
        if (r.nextDouble() >= 0.72) return "";

        double base = 7.0;
        base += m.vipFlag() ? 0.7 : 0.0;
        base -= m.qualitySensitivity() * 3.4;
        base -= m.billingSensitivity() * 2.7;
        base -= m.retentionSensitivity() * 1.5;
        base -= m.delinquencyRisk() * 2.2;
        if (m.complaintFlag()) base -= 1.0;
        if (invOpt.isPresent() && invOpt.get().overdueAmount() > 0) base -= 1.5;
        if ("OUT".equals(direction)) base += 0.4;
        if ("DORMANT".equalsIgnoreCase(m.status())) base -= 0.4;

        int score = (int)Math.round(base + (r.nextGaussian() * 1.4));
        score = Math.max(1, Math.min(10, score));
        return Integer.toString(score);
    }

    private List<Integer> collectLeafs(int rootId, Map<Integer, List<Integer>> childrenByParent) {
        List<Integer> leafs = new ArrayList<>();
        Deque<Integer> st = new ArrayDeque<>();
        st.push(rootId);
        while (!st.isEmpty()) {
            int cur = st.pop();
            List<Integer> ch = childrenByParent.get(cur);
            if (ch == null || ch.isEmpty()) leafs.add(cur);
            else for (int c : ch) st.push(c);
        }
        return leafs;
    }

    private int findLeafByKeyword(List<Integer> leafIds, Map<Integer, Category> catById, List<String> keywords) {
        if (leafIds == null || leafIds.isEmpty()) return -1;
        for (int id : leafIds) {
            Category c = catById.get(id);
            if (c == null) continue;
            String name = c.categoryName();
            for (String kw : keywords) {
                if (name != null && name.contains(kw)) return id;
            }
        }
        return -1;
    }

    private <T> T pickFrom(List<T> xs) { return xs.get(r.nextInt(xs.size())); }

    private List<Promotion> filterActivePromotions(List<Promotion> promos, YearMonth targetYm) {
        int ym = Integer.parseInt(DateUtil.ym(targetYm));
        List<Promotion> out = new ArrayList<>();
        for (Promotion p : promos) {
            int startYm = parseMetaYm(p.promotionDetail(), "start");
            int endYm = parseMetaYm(p.promotionDetail(), "end");
            if (startYm == -1 || endYm == -1) { out.add(p); continue; }
            if (ym >= startYm && ym <= endYm) out.add(p);
        }
        return out;
    }

    private int parseMetaYm(String detail, String key) {
        if (detail == null) return -1;
        String needle = key + "=";
        int idx = detail.indexOf(needle);
        if (idx < 0) return -1;
        int start = idx + needle.length();
        int end = Math.min(start + 8, detail.length());
        if (end - start < 6) return -1;
        String yyyymmdd = detail.substring(start, end).replaceAll("[^0-9]", "");
        if (yyyymmdd.length() < 6) return -1;
        try { return Integer.parseInt(yyyymmdd.substring(0, 6)); }
        catch (Exception e) { return -1; }
    }

    private static final class AdviceTimes {
        final LocalDateTime startAt;
        final LocalDateTime endAt;
        final LocalDateTime createdAt;
        AdviceTimes(LocalDateTime s, LocalDateTime e, LocalDateTime c) { this.startAt = s; this.endAt = e; this.createdAt = c; }
    }

    private AdviceTimes makeAdviceTimesForMember(MemberProfile m, YearMonth ym) {
        // 가입월에는 가입 시각 이전 상담이 나오면 분석 인과가 깨진다.
        LocalDateTime floor = null;
        int minDay = 1;
        if (YearMonth.from(m.createdAt()).equals(ym)) {
            minDay = m.createdAt().getDayOfMonth();
            floor = m.createdAt();
        }

        AdviceTimes t = makeAdviceTimes(ym.getYear(), ym.getMonthValue(), m, minDay);

        if (floor != null && t.createdAt.isBefore(floor)) {
            // floor 이후로 강제 이동(최소 5~60분 뒤)
            int addMin = RandomUtil.nextIntInclusive(r, 5, 60);
            LocalDateTime endAt = floor.plusMinutes(addMin);
            LocalDateTime startAt = endAt.minusMinutes(RandomUtil.nextIntInclusive(r, 2, 25));
            if (startAt.isBefore(floor)) startAt = floor;
            return new AdviceTimes(startAt, endAt, endAt);
        }
        return t;
    }

    // ✅ 정합성 고정: start_at <= end_at == created_at
    private AdviceTimes makeAdviceTimes(int year, int month, MemberProfile m, int minDay) {
        int maxDay = DateUtil.daysInMonthFixedFeb28(year, month);
        int safeMinDay = Math.max(1, Math.min(minDay, maxDay));
        int day = RandomUtil.nextIntInclusive(r, safeMinDay, maxDay);

        if (m.nightBias() > 0.58 && r.nextDouble() < 0.45) {
            for (int i = 0; i < 10; i++) {
                int d = RandomUtil.nextIntInclusive(r, safeMinDay, maxDay);
                java.time.DayOfWeek dow = java.time.LocalDate.of(year, month, d).getDayOfWeek();
                if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
                    day = d;
                    break;
                }
            }
        }

        int hour;
        if (m.nightBias() > 0.62) {
            int[] hs = {21,22,23,0,1,2,3};
            hour = hs[r.nextInt(hs.length)];
        } else if (m.nightBias() > 0.40 && r.nextDouble() < 0.35) {
            int[] hs = {20,21,22,23,0,1};
            hour = hs[r.nextInt(hs.length)];
        } else {
            hour = RandomUtil.nextIntInclusive(r, 9, 22);
        }
        int minute = (r.nextDouble() < 0.70) ? 0 : (r.nextDouble() < 0.50 ? 10 : 30);

        LocalDateTime endAt = LocalDateTime.of(year, month, day, hour, minute, 0);
        int durMin = RandomUtil.nextIntInclusive(r, 2, 25);
        LocalDateTime startAt = endAt.minusMinutes(durMin);

        LocalDateTime createdAt = endAt;
        LocalDateTime dayStart = LocalDateTime.of(year, month, day, 0, 0, 0);
        if (startAt.isBefore(dayStart)) startAt = dayStart;

        return new AdviceTimes(startAt, endAt, createdAt);
    }

    private String buildAdviceContent(
            boolean isBillingRoot,
            int categoryId,
            Category cat,
            Promotion promo,
            List<Plan> activeSubs,
            BillingGenerator.InvoiceState inv,
            String channel
    ) {
        String catName = (cat == null || cat.categoryName() == null) ? ("category#" + categoryId) : cat.categoryName();
        String planName = activeSubs.isEmpty() ? "" : activeSubs.get(0).productName();

        // 1) 결제/청구(여기서만 billed/paid/overdue 사용)
        if (isBillingRoot) {
            if (inv == null) {
                return "요금/청구 문의(" + catName + "): 청구 내역 확인 요청"
                        + (planName.isEmpty() ? "" : (" / " + planName)) + " (" + channel + ")";
            }

            if (inv.overdueAmount() > 0) {
                if (inv.paidAt() == null) {
                    return "연체/미납 안내(" + catName + "): base_month=" + inv.baseMonth()
                            + ", due=" + inv.dueDate()
                            + ", billed=" + inv.billedAmount()
                            + ", overdue=" + inv.overdueAmount()
                            + (planName.isEmpty() ? "" : (" / " + planName))
                            + " (" + channel + ")";
                } else {
                    return "연체 납부 확인(" + catName + "): base_month=" + inv.baseMonth()
                            + ", due=" + inv.dueDate()
                            + ", billed=" + inv.billedAmount()
                            + ", paid_at=" + inv.paidAt()
                            + (planName.isEmpty() ? "" : (" / " + planName))
                            + " (" + channel + ")";
                }
            }

            return "요금/청구 문의(" + catName + "): billed=" + inv.billedAmount()
                    + (inv.paidAt() == null ? ", paid_at=null" : (", paid_at=" + inv.paidAt()))
                    + (planName.isEmpty() ? "" : (" / " + planName))
                    + " (" + channel + ")";
        }

        // 2) 품질/장애
        if (containsAny(catName, List.of("통화","문자","데이터","속도","장애","로밍"))) {
            return "품질 문의(" + catName + "): 증상 점검/가이드 요청"
                    + (planName.isEmpty() ? "" : (" / " + planName))
                    + " (" + channel + ")";
        }

        // 3) 요금제/상품 (billing 사실 금지)
        if (containsAny(catName, List.of("요금제","부가","단말","기기"))) {
            if (catName.contains("요금제")) {
                return "요금제 변경 문의: 변경 가능 요금제/적용일/위약금 안내 요청"
                        + (planName.isEmpty() ? "" : (" (현재=" + planName + ")"))
                        + " (" + channel + ")";
            }
            if (catName.contains("부가")) {
                return "부가서비스 문의: 신청/해지/요금 안내 요청"
                        + (planName.isEmpty() ? "" : (" (현재=" + planName + ")"))
                        + " (" + channel + ")";
            }
            return "단말/기기 문의: 단말 설정/호환/구매 관련 안내 요청 (" + channel + ")";
        }

        // 4) 혜택/프로모션 (billing 사실 금지)
        if (containsAny(catName, List.of("프로모션","할인","쿠폰","멤버십","제휴"))) {
            if (promo != null) {
                return "혜택 문의(" + catName + "): \"" + promo.promotionName() + "\" 대상/조건/기간 문의 (" + channel + ")";
            }
            return "혜택 문의(" + catName + "): 적용 조건 문의 (" + channel + ")";
        }

        // 5) 가입/해지/변경
        if (containsAny(catName, List.of("해지","가입","명의","번호","변경"))) {
            return "가입/변경 문의(" + catName + "): 절차/필요서류/처리기간 문의 (" + channel + ")";
        }

        return "상담(" + catName + ") (" + channel + ")";
    }

    private boolean containsAny(String s, List<String> kws) {
        if (s == null) return false;
        for (String kw : kws) if (s.contains(kw)) return true;
        return false;
    }

    // ---------------------------------------------------------------------
    // OUT extra: usage/plan heuristics (no new tables, seed-stable)
    // ---------------------------------------------------------------------
    private double estimateAllowanceGb(long monthlyPrice) {
        if (monthlyPrice <= 0) return 0;
        if (monthlyPrice < 33000) return 5;
        if (monthlyPrice < 45000) return 10;
        if (monthlyPrice < 55000) return 20;
        if (monthlyPrice < 69000) return 50;
        if (monthlyPrice < 85000) return 100;
        return 150;
    }

    private double synthUsageGb(long seed, long memberId, YearMonth ym, double allowanceGb) {
        long ymKey = (ym.getYear() * 100L) + ym.getMonthValue();
        long mix = seed ^ (memberId * 0x9E3779B97F4A7C15L) ^ (ymKey * 0xC2B2AE3DL);
        Random rr = new Random(mix);
        double base = (allowanceGb > 0) ? allowanceGb * (0.4 + rr.nextDouble() * 0.5) : (0.5 + rr.nextDouble() * 5.0);
        if (rr.nextDouble() < 0.08) base *= (1.3 + rr.nextDouble() * 1.5);
        if (rr.nextDouble() < 0.10) base *= (0.05 + rr.nextDouble() * 0.20);
        return Math.max(0.0, base);
    }
}