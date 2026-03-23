package com.que.telecomdummy.gen;

import com.que.telecomdummy.config.GenerationPolicy;
import com.que.telecomdummy.model.*;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;
import com.que.telecomdummy.util.RandomUtil;
import com.que.telecomdummy.util.WeightedPicker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * invoice / invoice_detail / payment 생성(월별) - 정합성 강화판
 *
 * * [시간 인과율 및 도메인 정합성 교정 사항]
 * 1. 가입월 당월 과금 차단: 후불제 원칙에 맞추어 청구서 발행(isActiveInMonth)을 무조건 가입월의 익월부터 시작하도록 제한함.
 * 2. 누적 미납금(Carry-over Debt) 적용: 전월 미납 요금이 당월 청구서에 누적 합산되도록 반영.
 * 3. 청구-결제 타임라인 교정: 청구일(4일) 이후부터 결제일(15, 21, 25일) 사이에 정상 납부가 이루어지도록 시간축 정렬.
 */
public final class BillingGenerator {

    private final GenerationContext ctx;
    private final Random r;
    private final GenerationPolicy policy;

    private final MasterDataGenerator masterGen;
    private final MemberGenerator memberGen;
    private final SubscriptionGenerator subGen;

    private long nextInvoiceId = 1;
    private long nextInvoiceDetailId = 1;
    private long nextPaymentId = 1;

    // 미납 금액을 다음 달로 이월하기 위한 상태 추적 캐시 (누적 과금 모델 구현용)
    private final Map<Long, Long> carryOverDebtByMember = new HashMap<>();

    public BillingGenerator(GenerationContext ctx,
                            MasterDataGenerator masterGen,
                            MemberGenerator memberGen,
                            SubscriptionGenerator subGen,
                            GenerationPolicy policy) {
        this.ctx = ctx;
        this.policy = (policy == null) ? GenerationPolicy.defaults() : policy;
        this.masterGen = masterGen;
        this.memberGen = memberGen;
        this.subGen = subGen;
        this.r = new Random(ctx.seed() ^ 0xB11_11_6L); 
    }

    public record InvoiceState(
            long invoiceId,
            long memberId,
            int baseMonth,
            long billedAmount,
            long overdueAmount,
            LocalDate dueDate,
            LocalDateTime createdAt,
            Long paidAmount,
            String paidMethod,
            LocalDateTime paidAt
    ) {}

    private final Map<Long, InvoiceState> invoiceStateByKey = new HashMap<>();

    private static long key(long memberId, int month) {
        return (memberId << 6) ^ (month & 0x3F);
    }

    public Optional<InvoiceState> getInvoiceState(long memberId, int month) {
        return Optional.ofNullable(invoiceStateByKey.get(key(memberId, month)));
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("billing");
        Files.createDirectories(outDir);

        Path invPath = outDir.resolve("invoice.csv");
        Path detPath = outDir.resolve("invoice_detail.csv");
        Path payPath = outDir.resolve("payment.csv");

        try (
                CsvWriter invW = new CsvWriter(invPath, List.of(
                        "invoice_id","member_id","base_month","due_date","billed_amount","overdue_amount","created_at"
                ));
                CsvWriter detW = new CsvWriter(detPath, List.of(
                        "invoice_detail_id","invoice_id","product_id","product_name_snapshot","product_type","quantity",
                        "total_price","started_at","end_at"
                ));
                CsvWriter payW = new CsvWriter(payPath, List.of(
                        "payment_id","invoice_id","paid_amount","paid_method","paid_at","created_at"
                ))
        ) {
            Set<Long> forcedOverdueMembers = pickForcedOverdueMembers();

            for (int month : ctx.months()) {

                // 해당 월에 청구서가 발행되어야 하는 활성 회원 필터링 (가입월 제외됨)
                List<MemberProfile> actives = new ArrayList<>();
                for (MemberProfile m : memberGen.members()) {
                    if (isActiveInMonth(m, month)) actives.add(m);
                }

                class Row {
                    MemberProfile m;
                    long invoiceId;
                    long billedAmount;
                    long overdueAmount;
                    Long paidAmount;
                    String paidMethod;
                    LocalDateTime paidAt;
                    LocalDate dueDate;
                    LocalDateTime createdAt;
                    List<List<String>> detailRows = new ArrayList<>();
                }

                List<Row> rows = new ArrayList<>(actives.size());

                MasterData md = masterGen.master();
                List<Plan> oneTimePlans = md.plansByType().get("ONE_TIME");

                for (MemberProfile m : actives) {
                    Row row = new Row();
                    row.m = m;
                    row.invoiceId = nextInvoiceId++;
                    row.createdAt = DateUtil.atFixed(ctx.year(), month, 4, 9, 0, 0);
                    row.dueDate = LocalDate.of(ctx.year(), month, m.billingCycleDay());

                    long currentMonthBilled = 0;

                    // 1) 당월 구독 라인 생성
                    List<SubscriptionGenerator.SubPeriod> subs = subGen.getActiveSubscriptionPeriods(m.memberId(), month);
                    if (subs.size() > 3) subs = subs.subList(0, 3);

                    for (SubscriptionGenerator.SubPeriod sp : subs) {
                        Plan p = sp.plan();
                        long lineTotal = p.price();
                        currentMonthBilled += lineTotal;

                        row.detailRows.add(List.of(
                                Long.toString(nextInvoiceDetailId++),
                                Long.toString(row.invoiceId),
                                Long.toString(p.productId()),
                                p.productName(),
                                p.productType(),
                                "1",
                                Long.toString(lineTotal),
                                sp.startedAt().format(DateUtil.DT),
                                sp.endAt() == null ? "" : sp.endAt().format(DateUtil.DT)
                        ));
                    }

                    // 2) 당월 단건 라인 생성
                    int oneTimeCount = pickOneTimeCount(m);

                    for (int i = 0; i < oneTimeCount; i++) {
                        Plan p = oneTimePlans.get(r.nextInt(oneTimePlans.size()));
                        int day = RandomUtil.nextIntInclusive(r, 1, DateUtil.daysInMonthFixedFeb28(ctx.year(), month));
                        int hour = RandomUtil.nextIntInclusive(r, 9, 23);
                        LocalDateTime startedAt = LocalDateTime.of(ctx.year(), month, day, hour, 0, 0);

                        long lineTotal = p.price();
                        currentMonthBilled += lineTotal;

                        row.detailRows.add(List.of(
                                Long.toString(nextInvoiceDetailId++),
                                Long.toString(row.invoiceId),
                                Long.toString(p.productId()),
                                p.productName(),
                                p.productType(),
                                "1", 
                                Long.toString(lineTotal),
                                startedAt.format(DateUtil.DT),
                                ""
                        ));
                    }

                    // 3) 전월 누적 미납금(Carry-over debt) 합산
                    long carriedDebt = carryOverDebtByMember.getOrDefault(m.memberId(), 0L);
                    row.billedAmount = currentMonthBilled + carriedDebt;

                    // 4) 납부/연체 여부 판별
                    PaymentDecision pd;
                    if (!policy.forceOverdueIsTargetTotal && forcedOverdueMembers.contains(m.memberId())) {
                        pd = decideForcedOverdue(m, row.dueDate, row.billedAmount, month);
                    } else {
                        pd = decidePayment(m, row.dueDate, row.billedAmount, month);
                    }

                    row.overdueAmount = pd.overdueAmount;
                    row.paidAmount = pd.paidAmount;
                    row.paidMethod = pd.paidMethod;
                    row.paidAt = pd.paidAt;

                    // 다음 달 이월을 위해 현재 남은 미납금 상태 갱신
                    carryOverDebtByMember.put(m.memberId(), row.overdueAmount);

                    rows.add(row);
                }

                // CSV 적재 및 AdviceGenerator 상태 공유
                for (Row row : rows) {
                    invW.writeRow(List.of(
                            Long.toString(row.invoiceId),
                            Long.toString(row.m.memberId()),
                            String.format("%04d%02d", ctx.year(), month),
                            row.dueDate.toString(),
                            Long.toString(row.billedAmount),
                            Long.toString(row.overdueAmount),
                            row.createdAt.format(DateUtil.DT)
                    ));

                    for (List<String> d : row.detailRows) detW.writeRow(d);

                    payW.writeRow(List.of(
                            Long.toString(nextPaymentId++),
                            Long.toString(row.invoiceId),
                            row.paidAmount == null ? "" : Long.toString(row.paidAmount),
                            row.paidMethod == null ? "" : row.paidMethod,
                            row.paidAt == null ? "" : row.paidAt.format(DateUtil.DT),
                            row.createdAt.format(DateUtil.DT)
                    ));

                    invoiceStateByKey.put(
                            key(row.m.memberId(), month),
                            new InvoiceState(
                                    row.invoiceId, row.m.memberId(), month, row.billedAmount, row.overdueAmount,
                                    row.dueDate, row.createdAt, row.paidAmount, row.paidMethod, row.paidAt
                            )
                    );
                }
            }
        }
    }

    /**
     * [시간 인과율 교정 포인트]
     * 통신 요금은 후불제이므로, 가입월 당월에는 청구서가 생성되지 않습니다.
     * 무조건 가입월의 다음 달(signupMonth + 1)부터 과금 대상이 됩니다.
     */
    private boolean isActiveInMonth(MemberProfile m, int month) {
        // 가입 연도가 생성 연도(올해)인 경우 가입월 반환, 과거 연도 가입자면 0 처리
        int signupMonth = (m.createdAt().getYear() == ctx.year()) ? m.createdAt().getMonthValue() : 0;
        
        // 과금 시작은 가입월의 익월(다음 달)부터
        int startMonth = signupMonth + 1;
        int endMonth = (m.cancelMonth() == null) ? 12 : m.cancelMonth(); 
        
        return month >= startMonth && month <= endMonth;
    }

    private int pickOneTimeCount(MemberProfile m) {
        WeightedPicker<Integer> wp = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>(0, 0.70),
                new WeightedPicker.Entry<>(1, 0.20),
                new WeightedPicker.Entry<>(2, 0.07),
                new WeightedPicker.Entry<>(3, 0.02),
                new WeightedPicker.Entry<>(4, 0.01)
        ));
        int base = wp.pick(r);

        switch (m.archetype()) {
            case H2C_HEAVY_NIGHT_FOCUS -> base += (r.nextDouble() < 0.10 ? 1 : 0);
            case H2A_HEAVY_NO_COMPLAINT, H2E_HEAVY_MULTI_SUBS -> base += (r.nextDouble() < 0.07 ? 1 : 0);
            default -> {}
        }
        return Math.min(base, 20);
    }

    private Set<Long> pickForcedOverdueMembers() {
        Set<Long> out = new HashSet<>();
        double rate = policy.forceOverdueMemberRate;
        if (rate <= 0) return out;
        for (MemberProfile m : memberGen.members()) {
            if (r.nextDouble() < rate) out.add(m.memberId());
        }
        return out;
    }

    private static final class PaymentDecision {
        long overdueAmount;
        Long paidAmount;
        String paidMethod;
        LocalDateTime paidAt;
    }

    private PaymentDecision decidePayment(MemberProfile m, LocalDate dueDate, long billed, int month) {
        PaymentDecision pd = new PaymentDecision();

        if (billed <= 0) {
            pd.overdueAmount = 0;
            pd.paidAmount = 0L;
            pd.paidMethod = pickPaidMethod(m);
            pd.paidAt = dueDate.atTime(9, 0);
            return pd;
        }

        double overdueProb = baseOverdueProb(m);
        boolean overdue = r.nextDouble() < overdueProb;

        if (!overdue) {
            pd.overdueAmount = 0;
            pd.paidAmount = billed;
            pd.paidMethod = pickPaidMethod(m);
            // 청구일(4일)부터 결제일(15, 21, 25일) 사이 납부
            int paidDay = RandomUtil.nextIntInclusive(r, 4, dueDate.getDayOfMonth());
            pd.paidAt = DateUtil.atFixed(ctx.year(), month, paidDay,
                    RandomUtil.nextIntInclusive(r, 9, 22),
                    RandomUtil.nextIntInclusive(r, 0, 59),
                    RandomUtil.nextIntInclusive(r, 0, 59));
            return pd;
        }

        boolean unpaid = r.nextDouble() < policy.forceUnpaidWithinOverdue;
        if (unpaid) {
            pd.overdueAmount = billed;
            pd.paidAmount = null;
            pd.paidMethod = null;
            pd.paidAt = null;
            return pd;
        }

        long minRemain = Math.max(1, (long) (billed * 0.05));
        long remain = RandomUtil.nextLongInclusive(r, minRemain, Math.max(minRemain, (long) (billed * 0.5)));
        pd.overdueAmount = remain;
        pd.paidAmount = billed - remain;
        pd.paidMethod = pickPaidMethod(m);

        int maxDays = DateUtil.daysInMonthFixedFeb28(ctx.year(), month);
        int paidDay = Math.min(maxDays, dueDate.getDayOfMonth() + RandomUtil.nextIntInclusive(r, 1, 10));
        pd.paidAt = DateUtil.atFixed(ctx.year(), month, paidDay,
                RandomUtil.nextIntInclusive(r, 9, 22),
                RandomUtil.nextIntInclusive(r, 0, 59),
                RandomUtil.nextIntInclusive(r, 0, 59));
        return pd;
    }

    private PaymentDecision decideForcedOverdue(MemberProfile m, LocalDate dueDate, long billed, int month) {
        PaymentDecision pd = new PaymentDecision();

        if (billed <= 0) {
            pd.overdueAmount = 0;
            pd.paidAmount = 0L;
            pd.paidMethod = pickPaidMethod(m);
            pd.paidAt = dueDate.atTime(9, 0);
            return pd;
        }

        boolean unpaid = r.nextDouble() < policy.forceUnpaidWithinOverdue;
        if (unpaid) {
            pd.overdueAmount = billed;
            pd.paidAmount = null;
            pd.paidMethod = null;
            pd.paidAt = null;
            return pd;
        }

        long minRemain = Math.max(1, (long) (billed * 0.10));
        long remain = RandomUtil.nextLongInclusive(r, minRemain, Math.max(minRemain, (long) (billed * 0.7)));
        pd.overdueAmount = remain;
        pd.paidAmount = billed - remain;
        pd.paidMethod = pickPaidMethod(m);

        int maxDays = DateUtil.daysInMonthFixedFeb28(ctx.year(), month);
        int paidDay = Math.min(maxDays, dueDate.getDayOfMonth() + RandomUtil.nextIntInclusive(r, 2, 12));
        pd.paidAt = DateUtil.atFixed(ctx.year(), month, paidDay,
                RandomUtil.nextIntInclusive(r, 9, 22),
                RandomUtil.nextIntInclusive(r, 0, 59),
                RandomUtil.nextIntInclusive(r, 0, 59));
        return pd;
    }

    private double baseOverdueProb(MemberProfile m) {
        double p = 0.01;

        p += switch (m.archetype()) {
            case P3D_NEAR_DELINQUENT -> 0.08;
            case C4B_BILLING_DISPUTE -> 0.10;
            case D6C_DELINQUENT_CHURNED -> 0.20;
            default -> 0.0;
        };

        if (m.cancelMonth() != null) p += 0.02;

        p *= policy.baseAdviceIntensity;

        return Math.min(0.90, Math.max(0.0, p));
    }

    private String pickPaidMethod(MemberProfile m) {
        double x = r.nextDouble();
        if (x < 0.75) return "CARD";
        if (x < 0.95) return "ACCOUNT";
        return "POINT";
    }
}