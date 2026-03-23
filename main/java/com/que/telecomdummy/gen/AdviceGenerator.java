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
import java.util.*;

/**
 * advice 생성(월별) - 정합성 강화판
 * * * [주요 수정 사항]
 * 1. SSOT 연동: 자체적인 사용량 합성(synthUsageGb)을 폐기하고 UsageGenerator에서 캐싱된 실제 월 사용량을 주입받아 판단.
 * 2. 시간 인과율(Time Causality) 방어: 가입월(Signup Month)에 상담 내역이 가입 시각(createdAt) 
 * 이전으로 역행하지 않도록 날짜 풀(Pool)과 시각(Time)을 강제 클램핑(Clamping) 처리함.
 */
public final class AdviceGenerator {
    private final GenerationContext ctx;
    private final Random r;
    private final MasterDataGenerator masterGen;
    private final MemberGenerator memberGen;
    private final SubscriptionGenerator subGen;
    private final BillingGenerator billingGen;
    private final UsageGenerator usageGen; // SSOT 연동을 위한 의존성 주입
    private final GenerationPolicy policy;

    private long nextAdviceId = 1;

    public AdviceGenerator(GenerationContext ctx,
                           MasterDataGenerator masterGen,
                           MemberGenerator memberGen,
                           SubscriptionGenerator subGen,
                           BillingGenerator billingGen,
                           UsageGenerator usageGen,
                           GenerationPolicy policy) {
        this.ctx = ctx;
        this.masterGen = masterGen;
        this.memberGen = memberGen;
        this.subGen = subGen;
        this.billingGen = billingGen;
        this.usageGen = usageGen;
        this.policy = (policy == null) ? GenerationPolicy.defaults() : policy;
        this.r = new Random(ctx.seed() ^ 0xAD1CE12345L);
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("advice");
        Files.createDirectories(outDir);

        MasterData md = masterGen.master();
        List<AdminUser> admins = md.admins();
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

        int billingOverdueLeaf = findLeafByKeyword(leafByRoot.get(billingRoot), catById, List.of("연체", "미납", "납부"));
        int billingInvoiceLeaf = findLeafByKeyword(leafByRoot.get(billingRoot), catById, List.of("청구", "요금", "청구서"));
        int benefitPromoLeaf = findLeafByKeyword(leafByRoot.get(benefitRoot), catById, List.of("프로모션", "할인", "쿠폰", "멤버십", "제휴"));

        for (int month : ctx.months()) {
            String ym = DateUtil.ym(ctx.year(), month);
            Path path = outDir.resolve("advice_" + ym + ".csv");

            boolean isComplaintSpike = policy.complaintSpikeMonths.contains(month);
            boolean isPromoStorm = policy.promoStormMonths.contains(month);

            List<Promotion> activePromos = filterActivePromotions(promotions, ctx.year(), month);

            try (CsvWriter w = new CsvWriter(path, List.of(
                    "advice_id", "member_id", "admin_id", "category_id", "promotion_id", "direction", "channel",
                    "advice_content", "start_at", "end_at", "created_at", "satisfaction_score"
            ))) {

                for (MemberProfile m : memberGen.members()) {
                    if (!isActiveMonth(m, month)) continue;

                    Optional<BillingGenerator.InvoiceState> invOpt = billingGen.getInvoiceState(m.memberId(), month);

                    // (1) overdue/unpaid면 billing 상담 강제 생성
                    if (invOpt.isPresent() && invOpt.get().overdueAmount() > 0 && r.nextDouble() < policy.forcedOverdueAdviceRatio) {
                        BillingGenerator.InvoiceState inv = invOpt.get();

                        String forcedDirection = (inv.paidAt() == null) ? "OUT" : "IN";
                        String forcedChannel = (inv.paidAt() == null) ? pickFrom(List.of("SMS","CALL")) : pickFrom(List.of("CALL","APP"));
                        int forcedCategoryId = (billingOverdueLeaf != -1) ? billingOverdueLeaf : billingRoot;

                        AdviceTimes t = makeAdviceTimes(ctx.year(), month, m);

                        List<Plan> activeSubs = subGen.getActiveSubscriptionPlans(m.memberId(), month);
                        String content = buildAdviceContent(
                                true, forcedCategoryId, catById.get(forcedCategoryId), null, activeSubs, inv, forcedChannel
                        );

                        w.writeRow(List.of(
                                Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admins.get(r.nextInt(admins.size())).adminId()),
                                Integer.toString(forcedCategoryId), "", forcedDirection, forcedChannel, content,
                                t.startAt.format(DateUtil.DT), t.endAt.format(DateUtil.DT), t.createdAt.format(DateUtil.DT),
                                (inv.paidAt() == null && r.nextDouble() < 0.85) ? "" : Integer.toString(RandomUtil.nextIntInclusive(r, 1, 10))
                        ));

                        // 미납 재안내
                        if (inv.paidAt() == null && r.nextDouble() < 0.35) {
                            AdviceTimes t2 = makeAdviceTimes(ctx.year(), month, m);
                            w.writeRow(List.of(
                                    Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admins.get(r.nextInt(admins.size())).adminId()),
                                    Integer.toString(forcedCategoryId), "", "OUT", pickFrom(List.of("SMS","CALL")),
                                    "미납 재안내: base_month=" + inv.baseMonth() + ", overdue=" + inv.overdueAmount() + ", 납부 방법/기한 안내",
                                    t2.startAt.format(DateUtil.DT), t2.endAt.format(DateUtil.DT), t2.createdAt.format(DateUtil.DT), ""
                            ));
                        }
                    }

                    // (1.5) OUT 추가 케이스
                    if (invOpt.isPresent()) {
                        BillingGenerator.InvoiceState inv = invOpt.get();

                        // (A) 청구 급증
                        if (policy.outBillingAnomalyRate > 0 && month > 1) {
                            Optional<BillingGenerator.InvoiceState> prevOpt = billingGen.getInvoiceState(m.memberId(), month - 1);
                            if (prevOpt.isPresent()) {
                                long prevBilled = prevOpt.get().billedAmount();
                                if (prevBilled > 0 && inv.billedAmount() >= (long)Math.ceil(prevBilled * 1.5)
                                        && r.nextDouble() < policy.outBillingAnomalyRate) {
                                    AdviceTimes tA = makeAdviceTimes(ctx.year(), month, m);
                                    String chA = pickFrom(List.of("CALL","SMS"));
                                    String contentA = "청구 급증 확인 안내: prev_billed=" + prevBilled + ", billed=" + inv.billedAmount()
                                            + ", base_month=" + inv.baseMonth() + " (이상 과금/변동 사유 확인) (" + chA + ")";
                                    w.writeRow(List.of(
                                            Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admins.get(r.nextInt(admins.size())).adminId()),
                                            Integer.toString(billingRoot), "", "OUT", chA, contentA,
                                            tA.startAt.format(DateUtil.DT), tA.endAt.format(DateUtil.DT), tA.createdAt.format(DateUtil.DT), RandomUtil.maybeScore(r)
                                    ));
                                }
                            }
                        }

                        // (B) 자동납부 유도
                        if (policy.outAutoPayNudgeRate > 0 && inv.paidAt() != null && inv.paidAt().toLocalDate().isAfter(inv.dueDate())
                                && r.nextDouble() < policy.outAutoPayNudgeRate) {
                            AdviceTimes tB = makeAdviceTimes(ctx.year(), month, m);
                            String chB = pickFrom(List.of("CALL","APP","SMS"));
                            String contentB = "자동납부/결제수단 변경 안내: 납부지연 예방, 다음 달부터 자동이체/카드등록 권유 (base_month=" + inv.baseMonth() + ") (" + chB + ")";
                            w.writeRow(List.of(
                                    Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admins.get(r.nextInt(admins.size())).adminId()),
                                    Integer.toString(billingRoot), "", "OUT", chB, contentB,
                                    tB.startAt.format(DateUtil.DT), tB.endAt.format(DateUtil.DT), tB.createdAt.format(DateUtil.DT), RandomUtil.maybeScore(r)
                            ));
                        }

                        // (C) 사용량/요금제 최적화 OUT (SSOT 연동)
                        if (policy.outUsageOptimizeRate > 0) {
                            List<Plan> subsNow = subGen.getActiveSubscriptionPlans(m.memberId(), month);
                            Plan rep = subsNow.isEmpty() ? null : subsNow.get(0);
                            long price = (rep == null) ? 0 : rep.price();
                            double allowanceGb = estimateAllowanceGb(price);
                            
                            // SSOT: UsageGenerator로부터 실제 사용량을 가져옴
                            double usageGb = usageGen.getMonthlyUsageGb(m.memberId(), month);

                            boolean wantsUpsell = allowanceGb > 0 && usageGb >= allowanceGb * 1.2;
                            boolean wantsDownsell = price >= 55000 && allowanceGb > 0 && usageGb <= allowanceGb * 0.25;

                            if ((wantsUpsell || wantsDownsell) && r.nextDouble() < policy.outUsageOptimizeRate) {
                                AdviceTimes tC = makeAdviceTimes(ctx.year(), month, m);
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
                                            + "GB (현재=" + repName + ") -> 하위 요금제/절약 안내 (" + chC + ")";
                                }
                                w.writeRow(List.of(
                                        Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admins.get(r.nextInt(admins.size())).adminId()),
                                        Integer.toString(planRoot), "", "OUT", chC, contentC,
                                        tC.startAt.format(DateUtil.DT), tC.endAt.format(DateUtil.DT), tC.createdAt.format(DateUtil.DT), RandomUtil.maybeScore(r)
                                ));
                            }

                            // (D) 저활성 이탈 예방
                            if (policy.outChurnPreventionRate > 0 && price > 0 && price <= 33000 && usageGb <= Math.max(0.8, allowanceGb * 0.2)
                                    && r.nextDouble() < policy.outChurnPreventionRate) {
                                AdviceTimes tD = makeAdviceTimes(ctx.year(), month, m);
                                String chD = pickFrom(List.of("CALL","SMS"));
                                String repName = (rep == null) ? "" : rep.productName();
                                String contentD = "저활성 케어 안내: 최근 사용량 저하 감지(" + String.format(Locale.US, "%.1f", usageGb) + "GB)"
                                        + (repName.isEmpty() ? "" : (" (현재=" + repName + ")"))
                                        + " -> 앱/서비스 이용방법, 혜택/부가서비스 안내 (" + chD + ")";
                                w.writeRow(List.of(
                                        Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admins.get(r.nextInt(admins.size())).adminId()),
                                        Integer.toString(planRoot), "", "OUT", chD, contentD,
                                        tD.startAt.format(DateUtil.DT), tD.endAt.format(DateUtil.DT), tD.createdAt.format(DateUtil.DT), RandomUtil.maybeScore(r)
                                ));
                            }
                        }
                    }

                    // (2) 일반 상담 생성
                    int count = pickAdviceCountPerMonth(m, invOpt, month, isComplaintSpike);
                    for (int i = 0; i < count; i++) {
                        AdminUser admin = admins.get(r.nextInt(admins.size()));

                        int root = pickCategoryRootId(m, invOpt, billingRoot, qualityRoot, planRoot, benefitRoot, terminateRoot, etcRoot, isPromoStorm);
                        int categoryId = pickFrom(leafByRoot.get(root));

                        if (root == billingRoot && invOpt.isPresent() && invOpt.get().overdueAmount() == 0 && billingInvoiceLeaf != -1) {
                            categoryId = billingInvoiceLeaf;
                        }

                        Promotion promo = null;
                        String promotionId = "";
                        if (root == benefitRoot && !activePromos.isEmpty()) {
                            double attach = isPromoStorm ? policy.promoAttachRateStorm : policy.promoAttachRate;
                            if (r.nextDouble() < attach) {
                                promo = activePromos.get(r.nextInt(activePromos.size()));
                                promotionId = promo.promotionId();
                                if (benefitPromoLeaf != -1) categoryId = benefitPromoLeaf;
                            }
                        }

                        String direction = pickDirection(m.archetype());
                        String channel = pickChannel(m.archetype());

                        AdviceTimes t = makeAdviceTimes(ctx.year(), month, m);

                        List<Plan> activeSubs = subGen.getActiveSubscriptionPlans(m.memberId(), month);
                        String content = buildAdviceContent(
                                root == billingRoot, categoryId, catById.get(categoryId), promo, activeSubs, invOpt.orElse(null), channel
                        );

                        String satisfaction = "";
                        if (r.nextDouble() < 0.65) satisfaction = Integer.toString(RandomUtil.nextIntInclusive(r, 1, 10));

                        w.writeRow(List.of(
                                Long.toString(nextAdviceId++), Long.toString(m.memberId()), Long.toString(admin.adminId()),
                                Integer.toString(categoryId), promotionId, direction, channel, content,
                                t.startAt.format(DateUtil.DT), t.endAt.format(DateUtil.DT), t.createdAt.format(DateUtil.DT), satisfaction
                        ));
                    }
                }
            }
        }
    }

    private String pickDirection(Archetype a) {
        double outRate;
        switch (a) {
            case P3A_PROMO_RESPONSIVE:
            case V5C_CARE_VIP:
            case C4D_MULTI_CHANNEL_PERSISTENT:
            case C4E_CHURN_THREAT_COMPLAINT:
                outRate = 0.35;
                break;
            case P3C_DOWNGRADE_CONSIDERING:
            case D6A_USAGE_DROP_DORMANT:
                outRate = 0.25;
                break;
            default:
                outRate = 0.18;
                break;
        }
        return (r.nextDouble() < outRate) ? "OUT" : "IN";
    }

    private String pickChannel(Archetype a) {
        switch (a) {
            case C4D_MULTI_CHANNEL_PERSISTENT:
                return weightedPick(new String[]{"CALL","APP","SMS"}, new int[]{45,35,20});
            case L1A_UNINTERESTED_STABLE:
                return weightedPick(new String[]{"APP","CALL","SMS"}, new int[]{60,30,10});
            default:
                return weightedPick(new String[]{"CALL","APP","SMS"}, new int[]{55,35,10});
        }
    }

    private double baseLambdaByArchetype(Archetype a) {
        switch (a) {
            case L1A_UNINTERESTED_STABLE:  return 0.05;
            case L1B_REGULAR_LOW_USAGE:    return 0.10;
            case L1C_NIGHT_SIMPLE:         return 0.06;
            case L1D_SINGLE_INQUIRY:       return 0.20;
            case H2A_HEAVY_NO_COMPLAINT:       return 0.06;
            case H2B_HEAVY_QUALITY_SENSITIVE:  return 0.35;
            case H2C_HEAVY_NIGHT_FOCUS:        return 0.10;
            case H2D_HEAVY_BILLING_DISSATISFIED:return 0.30;
            case H2E_HEAVY_MULTI_SUBS:         return 0.15;
            case P3A_PROMO_RESPONSIVE:     return 0.25;
            case P3B_BILLING_TRACKER:      return 0.80;
            case P3C_DOWNGRADE_CONSIDERING:return 0.25;
            case P3D_NEAR_DELINQUENT:      return 0.35;
            case C4A_CHRONIC_QUALITY_COMPLAINT:return 1.20;
            case C4B_BILLING_DISPUTE:          return 1.00;
            case C4C_NIGHT_WEEKEND_COMPLAINT:  return 1.00;
            case C4D_MULTI_CHANNEL_PERSISTENT: return 1.60;
            case C4E_CHURN_THREAT_COMPLAINT:   return 0.90;
            case V5A_LONGTERM_CORE:            return 0.05;
            case V5B_VIP_PROMO_RESPONSIVE:     return 0.10;
            case V5C_CARE_VIP:                 return 0.25;
            case V5D_VIP_QUALITY_SENSITIVE:    return 0.30;
            case D6A_USAGE_DROP_DORMANT:       return 0.02;
            case D6B_DISSATISFIED_CHURNED:     return 0.60;
            case D6C_DELINQUENT_CHURNED:       return 0.50;
            default: return 0.12;
        }
    }

    private <T> T weightedPick(T[] items, int[] weights) {
        int sum = 0;
        for (int w : weights) sum += Math.max(0, w);
        if (sum <= 0) return items[0];

        int x = r.nextInt(sum);
        int acc = 0;
        for (int i = 0; i < items.length; i++) {
            acc += Math.max(0, weights[i]);
            if (x < acc) return items[i];
        }
        return items[items.length - 1];
    }

    private boolean isActiveMonth(MemberProfile m, int month) {
        int startMonth = (m.createdAt().getYear() == ctx.year()) ? m.createdAt().getMonthValue() : 1;
        int endMonth = (m.cancelMonth() == null) ? 12 : m.cancelMonth();
        return month >= startMonth && month <= endMonth;
    }

    private int pickAdviceCountPerMonth(MemberProfile m, Optional<BillingGenerator.InvoiceState> invOpt, int month, boolean isComplaintSpike) {
        double lambda = baseLambdaByArchetype(m.archetype());

        switch (m.usageSegment()) {
            case LIGHT -> lambda *= 0.85;
            case NORMAL -> { }
            case HEAVY -> lambda *= 1.10;
        }
        if (m.complaintFlag()) lambda += 0.20;
        if (m.vipFlag()) lambda += 0.02;
        if (invOpt.isPresent() && invOpt.get().overdueAmount() > 0) lambda += 0.18;
        if (invOpt.isPresent() && invOpt.get().paidAt() == null && invOpt.get().overdueAmount() > 0) lambda += 0.10;

        lambda *= policy.baseAdviceIntensity;
        if (isComplaintSpike) lambda *= policy.complaintSpikeMultiplier;

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

        boolean hasOverdue = invOpt.isPresent() && invOpt.get().overdueAmount() > 0;

        if (hasOverdue) {
            WeightedPicker<Integer> p = new WeightedPicker<>(List.of(
                    new WeightedPicker.Entry<>(billing, 50),
                    new WeightedPicker.Entry<>(plan, 15),
                    new WeightedPicker.Entry<>(benefit, isPromoStorm ? 15 : 10),
                    new WeightedPicker.Entry<>(quality, 10),
                    new WeightedPicker.Entry<>(terminate, 10),
                    new WeightedPicker.Entry<>(etc, 5)
            ));
            return p.pick(r);
        }

        if (m.complaintFlag()) {
            WeightedPicker<Integer> p = new WeightedPicker<>(List.of(
                    new WeightedPicker.Entry<>(quality, 45),
                    new WeightedPicker.Entry<>(billing, 20),
                    new WeightedPicker.Entry<>(terminate, 10),
                    new WeightedPicker.Entry<>(plan, 10),
                    new WeightedPicker.Entry<>(benefit, isPromoStorm ? 10 : 7),
                    new WeightedPicker.Entry<>(etc, 8)
            ));
            return p.pick(r);
        }

        WeightedPicker<Integer> p = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>(billing, 20),
                new WeightedPicker.Entry<>(plan, 25),
                new WeightedPicker.Entry<>(benefit, isPromoStorm ? 20 : 15),
                new WeightedPicker.Entry<>(quality, 15),
                new WeightedPicker.Entry<>(terminate, 10),
                new WeightedPicker.Entry<>(etc, 10)
        ));
        return p.pick(r);
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

    private List<Promotion> filterActivePromotions(List<Promotion> promos, int year, int month) {
        int ym = Integer.parseInt(String.format("%04d%02d", year, month));
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

    /**
     * [시간 인과율 교정]
     * 가입월일 경우 가입일(Day) 이후로만 날짜를 선택하도록 제한하고, 
     * 시각이 가입 시각 이전이라면 가입 시각 이후로 강제 시프트(클램핑) 처리합니다.
     */
    private AdviceTimes makeAdviceTimes(int year, int month, MemberProfile m) {
        int maxDay = DateUtil.daysInMonthFixedFeb28(year, month);
        int minDay = 1;

        // 가입월인 경우 가입일 이전 날짜를 배제
        if (m.createdAt().getYear() == year && m.createdAt().getMonthValue() == month) {
            minDay = m.createdAt().getDayOfMonth();
        }

        int day = RandomUtil.nextIntInclusive(r, minDay, maxDay);

        if (m.archetype() == Archetype.C4C_NIGHT_WEEKEND_COMPLAINT) {
            for (int i = 0; i < 10; i++) {
                int d = RandomUtil.nextIntInclusive(r, minDay, maxDay);
                java.time.DayOfWeek dow = java.time.LocalDate.of(year, month, d).getDayOfWeek();
                if (dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY) {
                    day = d;
                    break;
                }
            }
        }

        int hour;
        if (m.archetype() == Archetype.L1C_NIGHT_SIMPLE || m.archetype() == Archetype.H2C_HEAVY_NIGHT_FOCUS || m.archetype() == Archetype.C4C_NIGHT_WEEKEND_COMPLAINT) {
            int[] hs = {21,22,23,0,1,2,3};
            hour = hs[r.nextInt(hs.length)];
        } else {
            hour = RandomUtil.nextIntInclusive(r, 9, 22);
        }
        int minute = (r.nextDouble() < 0.70) ? 0 : (r.nextDouble() < 0.50 ? 10 : 30);

        LocalDateTime endAt = LocalDateTime.of(year, month, day, hour, minute, 0);
        int durMin = RandomUtil.nextIntInclusive(r, 2, 25);
        LocalDateTime startAt = endAt.minusMinutes(durMin);

        // 시간 인과율 방어: 가입 시각보다 상담 시작 시각이 앞선다면 뒤로 밀어냄
        if (startAt.isBefore(m.createdAt())) {
            startAt = m.createdAt().plusMinutes(RandomUtil.nextIntInclusive(r, 1, 60));
            endAt = startAt.plusMinutes(durMin);
            
            // 만약 시간을 밀어내서 자정을 넘겼다면 동일 일자 내로 맞춤
            if (endAt.getDayOfMonth() != day || endAt.getMonthValue() != month) {
                endAt = LocalDateTime.of(year, month, day, 23, 59, 59);
                startAt = endAt.minusMinutes(durMin);
            }
        }

        // 혹시 모를 전날 역행 방지
        LocalDateTime dayStart = LocalDateTime.of(year, month, day, 0, 0, 0);
        if (startAt.isBefore(dayStart)) {
            startAt = dayStart;
            // 위에서 createdAt 방어가 있으나 이중 안전장치
            if (startAt.isBefore(m.createdAt())) {
                 startAt = m.createdAt();
            }
        }

        LocalDateTime createdAt = endAt;

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

        if (isBillingRoot) {
            if (inv == null) {
                return "요금/청구 문의(" + catName + "): 청구 내역 확인 요청" + (planName.isEmpty() ? "" : (" / " + planName)) + " (" + channel + ")";
            }
            if (inv.overdueAmount() > 0) {
                if (inv.paidAt() == null) {
                    return "연체/미납 안내(" + catName + "): base_month=" + inv.baseMonth() + ", due=" + inv.dueDate()
                            + ", billed=" + inv.billedAmount() + ", overdue=" + inv.overdueAmount()
                            + (planName.isEmpty() ? "" : (" / " + planName)) + " (" + channel + ")";
                } else {
                    return "연체 납부 확인(" + catName + "): base_month=" + inv.baseMonth() + ", due=" + inv.dueDate()
                            + ", billed=" + inv.billedAmount() + ", paid_at=" + inv.paidAt()
                            + (planName.isEmpty() ? "" : (" / " + planName)) + " (" + channel + ")";
                }
            }
            return "요금/청구 문의(" + catName + "): billed=" + inv.billedAmount()
                    + (inv.paidAt() == null ? ", paid_at=null" : (", paid_at=" + inv.paidAt()))
                    + (planName.isEmpty() ? "" : (" / " + planName)) + " (" + channel + ")";
        }

        if (containsAny(catName, List.of("통화","문자","데이터","속도","장애","로밍"))) {
            return "품질 문의(" + catName + "): 증상 점검/가이드 요청" + (planName.isEmpty() ? "" : (" / " + planName)) + " (" + channel + ")";
        }

        if (containsAny(catName, List.of("요금제","부가","단말","기기"))) {
            if (catName.contains("요금제")) {
                return "요금제 변경 문의: 변경 가능 요금제/적용일/위약금 안내 요청" + (planName.isEmpty() ? "" : (" (현재=" + planName + ")")) + " (" + channel + ")";
            }
            if (catName.contains("부가")) {
                return "부가서비스 문의: 신청/해지/요금 안내 요청" + (planName.isEmpty() ? "" : (" (현재=" + planName + ")")) + " (" + channel + ")";
            }
            return "단말/기기 문의: 단말 설정/호환/구매 관련 안내 요청 (" + channel + ")";
        }

        if (containsAny(catName, List.of("프로모션","할인","쿠폰","멤버십","제휴"))) {
            if (promo != null) {
                return "혜택 문의(" + catName + "): \"" + promo.promotionName() + "\" 대상/조건/기간 문의 (" + channel + ")";
            }
            return "혜택 문의(" + catName + "): 적용 조건 문의 (" + channel + ")";
        }

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

    private double estimateAllowanceGb(long monthlyPrice) {
        if (monthlyPrice <= 0) return 0;
        if (monthlyPrice < 33000) return 5;
        if (monthlyPrice < 45000) return 10;
        if (monthlyPrice < 55000) return 20;
        if (monthlyPrice < 69000) return 50;
        if (monthlyPrice < 85000) return 100;
        return 150;
    }
}