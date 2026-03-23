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
import java.time.YearMonth;
import java.util.*;

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

    private final Map<String, InvoiceState> invoiceStateByKey = new HashMap<>();

    private static String key(long memberId, YearMonth ym) {
        return memberId + "@" + ym;
    }

    public Optional<InvoiceState> getInvoiceState(long memberId, YearMonth ym) {
        return Optional.ofNullable(invoiceStateByKey.get(key(memberId, ym)));
    }

    public BillingGenerator(GenerationContext ctx,
                            MasterDataGenerator masterGen,
                            MemberGenerator memberGen,
                            SubscriptionGenerator subGen,
                            GenerationPolicy policy) {
        this.ctx = ctx;
        this.policy = policy;
        this.masterGen = masterGen;
        this.memberGen = memberGen;
        this.subGen = subGen;
        this.r = new Random(ctx.seed() ^ 0xB11_11_6L);
    }

    public void generate() throws Exception {
        Path outDir = ctx.outDir().resolve("billing");
        Files.createDirectories(outDir);

        Path invPath = outDir.resolve("invoice.csv");
        Path payPath = outDir.resolve("payment.csv");

        try (
                CsvWriter invW = new CsvWriter(invPath, List.of(
                        "invoice_id","member_id","base_month","due_date","billed_amount","overdue_amount","created_at"
                ));
                CsvWriter payW = new CsvWriter(payPath, List.of(
                        "payment_id","invoice_id","paid_amount","paid_method","paid_at","created_at"
                ))
        ) {
            Set<Long> forcedOverdueMembers = pickForcedOverdueMembers();

            for (YearMonth ym : ctx.periods()) {
                int month = ym.getMonthValue();
                int year = ym.getYear();
                Path detPath = outDir.resolve("invoice_detail_" + DateUtil.ym(ym) + ".csv");

                try (CsvWriter detW = new CsvWriter(detPath, List.of(
                        "invoice_detail_id","invoice_id","product_id","product_name_snapshot","product_type","quantity",
                        "total_price","started_at","end_at"
                ))) {
                    List<MemberProfile> actives = new ArrayList<>();
                    for (MemberProfile m : memberGen.members()) {
                        if (!isActiveInMonth(m, ym)) continue;
                        actives.add(m);
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
                        row.createdAt = DateUtil.atFixed(year, month, 4, 9, 0, 0);
                        row.dueDate = LocalDate.of(year, month, m.billingCycleDay());

                        if (row.dueDate.isBefore(row.createdAt.toLocalDate())) {
                            LocalDate adj = row.createdAt.toLocalDate().plusDays(7);
                            if (adj.isAfter(ym.atEndOfMonth())) adj = ym.atEndOfMonth();
                            row.dueDate = adj;
                        }

                        long billed = 0;
                        List<SubscriptionGenerator.SubPeriod> subs = subGen.getActiveSubscriptionPeriods(m.memberId(), ym);
                        if (subs.size() > 3) subs = subs.subList(0, 3);

                        for (SubscriptionGenerator.SubPeriod sp : subs) {
                            Plan p2 = sp.plan();
                            long lineTotal = p2.price();
                            billed += lineTotal;
                            row.detailRows.add(List.of(
                                    Long.toString(nextInvoiceDetailId++),
                                    Long.toString(row.invoiceId),
                                    Long.toString(p2.productId()),
                                    p2.productName(),
                                    p2.productType(),
                                    "1",
                                    Long.toString(lineTotal),
                                    sp.startedAt().format(DateUtil.DT),
                                    sp.endAt() == null ? "" : sp.endAt().format(DateUtil.DT)
                            ));
                        }

                        int oneTimeCount = pickOneTimeCount(m);
                        for (int i = 0; i < oneTimeCount; i++) {
                            Plan p2 = oneTimePlans.get(r.nextInt(oneTimePlans.size()));
                            int day = RandomUtil.nextIntInclusive(r, 1, DateUtil.daysInMonthFixedFeb28(year, month));
                            int hour = RandomUtil.nextIntInclusive(r, 9, 23);
                            LocalDateTime startedAt = LocalDateTime.of(year, month, day, hour, 0, 0);

                            long lineTotal = p2.price();
                            billed += lineTotal;

                            row.detailRows.add(List.of(
                                    Long.toString(nextInvoiceDetailId++),
                                    Long.toString(row.invoiceId),
                                    Long.toString(p2.productId()),
                                    p2.productName(),
                                    p2.productType(),
                                    "1",
                                    Long.toString(lineTotal),
                                    startedAt.format(DateUtil.DT),
                                    ""
                            ));
                        }

                        row.billedAmount = billed;

                        PaymentDecision pd = (!policy.forceOverdueIsTargetTotal && forcedOverdueMembers.contains(m.memberId()))
                                ? decideForcedOverdue(m, row.dueDate, billed, ym)
                                : decidePayment(m, row.dueDate, billed, ym);

                        row.overdueAmount = pd.overdueAmount;
                        row.paidAmount = pd.paidAmount;
                        row.paidMethod = pd.paidMethod;
                        row.paidAt = pd.paidAt;
                        rows.add(row);
                    }

                    for (Row row : rows) {
                        invW.writeRow(List.of(
                                Long.toString(row.invoiceId),
                                Long.toString(row.m.memberId()),
                                DateUtil.ym(ym),
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
                                key(row.m.memberId(), ym),
                                new InvoiceState(
                                        row.invoiceId,
                                        row.m.memberId(),
                                        Integer.parseInt(DateUtil.ym(ym)),
                                        row.billedAmount,
                                        row.overdueAmount,
                                        row.dueDate,
                                        row.createdAt,
                                        row.paidAmount,
                                        row.paidMethod,
                                        row.paidAt
                                )
                        );
                    }
                }
            }
        }
    }

    private boolean isActiveInMonth(MemberProfile m, YearMonth ym) {
        YearMonth signupYm = YearMonth.from(m.createdAt());
        YearMonth startYm = signupYm.isBefore(ctx.fromYm()) ? ctx.fromYm() : signupYm.plusMonths(1);
        if (ym.isBefore(startYm)) return false;
        return m.cancelYm() == null || !ym.isAfter(m.cancelYm());
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
        base += (m.multiSubAffinity() > 0.65 && r.nextDouble() < 0.10) ? 1 : 0;
        base += (m.billingSensitivity() > 0.55 && r.nextDouble() < 0.06) ? 1 : 0;
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

    private PaymentDecision decidePayment(MemberProfile m, LocalDate dueDate, long billed, YearMonth ym) {
        PaymentDecision pd = new PaymentDecision();
        int year = ym.getYear();
        int month = ym.getMonthValue();

        double overdueProb = baseOverdueProb(m);
        boolean overdue = r.nextDouble() < overdueProb;

        if (!overdue) {
            pd.overdueAmount = 0;
            pd.paidAmount = billed;
            pd.paidMethod = pickPaidMethod(m);
            pd.paidAt = DateUtil.atFixed(year, month, dueDate.getDayOfMonth(),
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

        int paidDay = Math.min(DateUtil.daysInMonthFixedFeb28(year, month), dueDate.getDayOfMonth() + RandomUtil.nextIntInclusive(r, 1, 10));
        pd.paidAt = DateUtil.atFixed(year, month, paidDay,
                RandomUtil.nextIntInclusive(r, 9, 22),
                RandomUtil.nextIntInclusive(r, 0, 59),
                RandomUtil.nextIntInclusive(r, 0, 59));
        return pd;
    }

    private PaymentDecision decideForcedOverdue(MemberProfile m, LocalDate dueDate, long billed, YearMonth ym) {
        PaymentDecision pd = new PaymentDecision();
        int year = ym.getYear();
        int month = ym.getMonthValue();

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

        int paidDay = Math.min(DateUtil.daysInMonthFixedFeb28(year, month), dueDate.getDayOfMonth() + RandomUtil.nextIntInclusive(r, 2, 12));
        pd.paidAt = DateUtil.atFixed(year, month, paidDay,
                RandomUtil.nextIntInclusive(r, 9, 22),
                RandomUtil.nextIntInclusive(r, 0, 59),
                RandomUtil.nextIntInclusive(r, 0, 59));
        return pd;
    }

    private double baseOverdueProb(MemberProfile m) {
        double p = 0.006;
        p += m.delinquencyRisk() * 0.16;
        p += m.billingSensitivity() * 0.03;
        p += m.retentionSensitivity() * 0.02;
        if (m.cancelYm() != null) p += 0.02;
        if ("DORMANT".equalsIgnoreCase(m.status())) p += 0.015;
        if (m.vipFlag()) p -= 0.005;

        p += switch (m.archetype()) {
            case P3B_BILLING_TRACKER -> 0.002;
            case P3C_DOWNGRADE_CONSIDERING -> 0.006;
            case P3D_NEAR_DELINQUENT -> 0.020;
            case H2D_HEAVY_BILLING_DISSATISFIED -> 0.005;
            case C4B_BILLING_DISPUTE -> 0.030;
            case C4E_CHURN_THREAT_COMPLAINT -> 0.008;
            case D6B_DISSATISFIED_CHURNED -> 0.012;
            case D6C_DELINQUENT_CHURNED -> 0.050;
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP -> -0.003;
            default -> 0.0;
        };

        p *= Math.max(0.70, policy.baseAdviceIntensity);
        return Math.min(0.90, Math.max(0.0, p));
    }

    private String pickPaidMethod(MemberProfile m) {
        double x = r.nextDouble();
        if (x < 0.75) return "CARD";
        if (x < 0.95) return "ACCOUNT";
        return "POINT";
    }
}
