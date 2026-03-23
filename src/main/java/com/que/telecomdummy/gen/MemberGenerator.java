package com.que.telecomdummy.gen;

import com.que.telecomdummy.model.MemberProfile;
import com.que.telecomdummy.model.Segment.UsageSegment;
import com.que.telecomdummy.model.Segment.Archetype;
import com.que.telecomdummy.model.GenerationContext;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;
import com.que.telecomdummy.util.RandomUtil;
import com.que.telecomdummy.util.WeightedPicker;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

public final class MemberGenerator {
    private final GenerationContext ctx;
    private final Random r;
    private final MasterDataGenerator masterGen;
    private List<MemberProfile> members;

    public MemberGenerator(GenerationContext ctx, MasterDataGenerator masterGen) {
        this.ctx = ctx;
        this.masterGen = masterGen;
        this.r = new Random(ctx.seed() ^ 0xC0FFEE1234L);
    }

    public List<MemberProfile> members() {
        if (members == null) throw new IllegalStateException("members not generated");
        return members;
    }

    public void generate() throws Exception {
        Path out = ctx.outDir().resolve("members");
        members = new ArrayList<>(ctx.memberCount());

        // region weights (resident-population-ish; tweakable)
        Map<String, Double> regionWeights = defaultRegionWeights();

        WeightedPicker<String> regionPicker = new WeightedPicker<>(
                regionWeights.entrySet().stream()
                        .map(e -> new WeightedPicker.Entry<>(e.getKey(), e.getValue()))
                        .toList()
        );

        WeightedPicker<String> genderPicker = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>("M", 50),
                new WeightedPicker.Entry<>("F", 50)
        ));

        WeightedPicker<Archetype> archetypePicker = new WeightedPicker<>(List.of(
                // 1) 일반 라이트(35%)
                new WeightedPicker.Entry<>(Archetype.L1A_UNINTERESTED_STABLE, 12),
                new WeightedPicker.Entry<>(Archetype.L1B_REGULAR_LOW_USAGE, 12),
                new WeightedPicker.Entry<>(Archetype.L1C_NIGHT_SIMPLE, 6),
                new WeightedPicker.Entry<>(Archetype.L1D_SINGLE_INQUIRY, 5),

                // 2) 데이터 헤비(25%)
                new WeightedPicker.Entry<>(Archetype.H2A_HEAVY_NO_COMPLAINT, 10),
                new WeightedPicker.Entry<>(Archetype.H2B_HEAVY_QUALITY_SENSITIVE, 7),
                new WeightedPicker.Entry<>(Archetype.H2C_HEAVY_NIGHT_FOCUS, 5),
                new WeightedPicker.Entry<>(Archetype.H2D_HEAVY_BILLING_DISSATISFIED, 2),
                new WeightedPicker.Entry<>(Archetype.H2E_HEAVY_MULTI_SUBS, 1),

                // 3) 가격 민감(20%)
                new WeightedPicker.Entry<>(Archetype.P3A_PROMO_RESPONSIVE, 8),
                new WeightedPicker.Entry<>(Archetype.P3B_BILLING_TRACKER, 6),
                new WeightedPicker.Entry<>(Archetype.P3C_DOWNGRADE_CONSIDERING, 4),
                new WeightedPicker.Entry<>(Archetype.P3D_NEAR_DELINQUENT, 2),

                // 4) 잦은 민원(12%)
                new WeightedPicker.Entry<>(Archetype.C4A_CHRONIC_QUALITY_COMPLAINT, 4),
                new WeightedPicker.Entry<>(Archetype.C4B_BILLING_DISPUTE, 3),
                new WeightedPicker.Entry<>(Archetype.C4C_NIGHT_WEEKEND_COMPLAINT, 2),
                new WeightedPicker.Entry<>(Archetype.C4D_MULTI_CHANNEL_PERSISTENT, 2),
                new WeightedPicker.Entry<>(Archetype.C4E_CHURN_THREAT_COMPLAINT, 1),

                // 5) 장기 VIP(5%)
                new WeightedPicker.Entry<>(Archetype.V5A_LONGTERM_CORE, 2),
                new WeightedPicker.Entry<>(Archetype.V5B_VIP_PROMO_RESPONSIVE, 1),
                new WeightedPicker.Entry<>(Archetype.V5C_CARE_VIP, 1),
                new WeightedPicker.Entry<>(Archetype.V5D_VIP_QUALITY_SENSITIVE, 1),

                // 6) 휴면/이탈(3%)
                new WeightedPicker.Entry<>(Archetype.D6A_USAGE_DROP_DORMANT, 2),
                new WeightedPicker.Entry<>(Archetype.D6B_DISSATISFIED_CHURNED, 1),
                new WeightedPicker.Entry<>(Archetype.D6C_DELINQUENT_CHURNED, 1)
        ));

        WeightedPicker<Integer> householdPicker = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>(1, 35),
                new WeightedPicker.Entry<>(2, 25),
                new WeightedPicker.Entry<>(3, 20),
                new WeightedPicker.Entry<>(4, 20)
        ));

        WeightedPicker<Integer> billingDayPicker = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>(5, 1),
                new WeightedPicker.Entry<>(15, 1),
                new WeightedPicker.Entry<>(25, 1)
        ));

        LocalDate createdStart = ctx.fromYm().minusYears(2).atDay(1);
        LocalDate createdEnd = ctx.toYm().atEndOfMonth();

        for (int i = 1; i <= ctx.memberCount(); i++) {
            long memberId = i;

            String gender = genderPicker.pick(r);
            LocalDate birth = randomBirthDate(r);
            String birthStr = birth.format(DateUtil.D);

            String region = regionPicker.pick(r);
            String address = fakeAddress(region, memberId);

            int householdType = householdPicker.pick(r);

            // 세그먼트(겹칠 수 있음)
            Archetype archetype = archetypePicker.pick(r);
            LocalDate createdDate = pickCreatedDate(archetype, createdStart, createdEnd);
            // 가입시간은 현실적으로 오전/오후에 분포
            int hour = RandomUtil.nextIntInclusive(r, 9, 22);
            LocalDateTime createdAt = createdDate.atTime(hour, 0, 0);

            UsageSegment usageSegment = deriveUsageSegment(archetype);
            LatentTraits traits = sampleLatentTraits(archetype, usageSegment);
            boolean vipFlag = deriveVipFlag(archetype, traits, r);
            boolean complaintFlag = deriveComplaintFlag(archetype, traits, r);

            // 해지(연 단위로 약하게). 해지 달까지 invoice 생성.
            YearMonth cancelYm = decideCancelYm(createdAt, archetype, usageSegment, vipFlag, complaintFlag, traits);

            // status는 archetype 의미를 반영해 초기값을 고정한다.
            String status = deriveStatus(archetype, cancelYm);

            int billingCycleDay = billingDayPicker.pick(r);

            String name = fakeName(memberId);
            String phone = fakePhone(memberId);
            String email = "member" + memberId + "@example.com";

            members.add(new MemberProfile(
                    memberId, name, phone, email, gender, birthStr, region, address,
                    householdType, createdAt, status, usageSegment, vipFlag, complaintFlag, billingCycleDay, cancelYm, archetype,
                    traits.contactPropensity,
                    traits.promoAffinity,
                    traits.billingSensitivity,
                    traits.qualitySensitivity,
                    traits.retentionSensitivity,
                    traits.nightBias,
                    traits.outboundAffinity,
                    traits.multiSubAffinity,
                    traits.delinquencyRisk
            ));
        }

        writeMember(out.resolve("member.csv"));
        writeConsent(out.resolve("member_consent.csv"));
    }

    private void writeMember(Path path) throws Exception {
        try (CsvWriter w = new CsvWriter(path, List.of(
                "member_id","name","phone","email","gender","birth_date","region","address","household_type","created_at","status"
        ))) {
            for (MemberProfile m : members) {
                w.writeRow(List.of(
                        Long.toString(m.memberId()),
                        m.name(),
                        m.phone(),
                        m.email(),
                        m.gender(),
                        m.birthDate(),
                        m.region(),
                        m.address(),
                        Integer.toString(m.householdType()),
                        m.createdAt().format(DateUtil.DT),
                        m.status()
                ));
            }
        }
    }

        private void writeConsent(Path path) throws Exception {
        // 세그먼트 정의와 맞추기 위해 consent를 archetype 기반으로 차등 생성한다.
        try (CsvWriter w = new CsvWriter(path, List.of(
                "member_consent_id","member_id","personal_accepted","marketing_accepted","is_converted","accepted_at","expires_at"
        ))) {
            long id = 1;
            for (MemberProfile m : members) {
                double personalYes = 0.98;
                double marketingYes = marketingAcceptRate(m);
                double convertedYes = conversionRate(m);

                String personal = (r.nextDouble() < personalYes) ? "Y" : "N";
                String marketing = (r.nextDouble() < marketingYes) ? "Y" : "N";
                String converted = ("Y".equals(marketing) && r.nextDouble() < convertedYes) ? "Y" : "N";

                LocalDateTime acceptedAt = m.createdAt().plusDays(RandomUtil.nextIntInclusive(r, 0, 7));
                String expires = (r.nextDouble() < 0.78)
                        ? acceptedAt.plusDays(RandomUtil.nextIntInclusive(r, 365, 365*3)).format(DateUtil.DT)
                        : "";

                w.writeRow(List.of(
                        Long.toString(id++),
                        Long.toString(m.memberId()),
                        personal,
                        marketing,
                        converted,
                        acceptedAt.format(DateUtil.DT),
                        expires
                ));
            }
        }
    }

    private LocalDate pickCreatedDate(Archetype archetype, LocalDate createdStart, LocalDate createdEnd) {
        LocalDate from = createdStart;
        LocalDate to = createdEnd;

        switch (archetype) {
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> {
                // 장기 VIP는 오래된 가입자를 우선한다.
                from = createdStart;
                to = LocalDate.of(ctx.toYm().getYear() - 1, 6, 30);
            }
            case D6A_USAGE_DROP_DORMANT -> {
                from = createdStart;
                to = LocalDate.of(ctx.toYm().getYear() - 1, 9, 30);
            }
            case D6B_DISSATISFIED_CHURNED, D6C_DELINQUENT_CHURNED, P3C_DOWNGRADE_CONSIDERING, C4E_CHURN_THREAT_COMPLAINT -> {
                from = LocalDate.of(ctx.toYm().getYear() - 1, 1, 1);
                to = createdEnd;
            }
            case L1D_SINGLE_INQUIRY -> {
                from = LocalDate.of(ctx.toYm().getYear() - 1, 6, 1);
                to = createdEnd;
            }
            default -> {
            }
        }
        if (to.isBefore(from)) to = from;
        return DateUtil.randomDate(r, from, to);
    }

    private String deriveStatus(Archetype archetype, YearMonth cancelYm) {
        if (cancelYm != null) return "TERMINATED";
        return switch (archetype) {
            case D6A_USAGE_DROP_DORMANT -> "DORMANT";
            default -> (r.nextDouble() < 0.02 ? "DORMANT" : "ACTIVE");
        };
    }

    private double marketingAcceptRate(MemberProfile m) {
        double rate = 0.20 + (m.promoAffinity() * 0.55) + (m.outboundAffinity() * 0.08);
        if (m.vipFlag()) rate += 0.06;
        if ("DORMANT".equalsIgnoreCase(m.status())) rate -= 0.12;
        if (m.cancelYm() != null) rate -= 0.06;
        return clamp01(rate);
    }

    private double conversionRate(MemberProfile m) {
        double rate = 0.02 + (m.promoAffinity() * 0.30) + (m.outboundAffinity() * 0.06);
        if (m.vipFlag()) rate += 0.03;
        if (m.complaintFlag()) rate -= 0.04;
        if (m.cancelYm() != null) rate -= 0.03;
        return clamp01(rate);
    }


    private static final class LatentTraits {
        final double contactPropensity;
        final double promoAffinity;
        final double billingSensitivity;
        final double qualitySensitivity;
        final double retentionSensitivity;
        final double nightBias;
        final double outboundAffinity;
        final double multiSubAffinity;
        final double delinquencyRisk;

        LatentTraits(double contactPropensity, double promoAffinity, double billingSensitivity, double qualitySensitivity,
                     double retentionSensitivity, double nightBias, double outboundAffinity, double multiSubAffinity,
                     double delinquencyRisk) {
            this.contactPropensity = contactPropensity;
            this.promoAffinity = promoAffinity;
            this.billingSensitivity = billingSensitivity;
            this.qualitySensitivity = qualitySensitivity;
            this.retentionSensitivity = retentionSensitivity;
            this.nightBias = nightBias;
            this.outboundAffinity = outboundAffinity;
            this.multiSubAffinity = multiSubAffinity;
            this.delinquencyRisk = delinquencyRisk;
        }
    }

    private LatentTraits sampleLatentTraits(Archetype a, UsageSegment usageSegment) {
        double contact = sampleAround(0.18, 0.10);
        double promo = sampleAround(0.24, 0.14);
        double billing = sampleAround(0.22, 0.14);
        double quality = sampleAround(0.22, 0.14);
        double retention = sampleAround(0.20, 0.12);
        double night = sampleAround(0.18, 0.12);
        double outbound = sampleAround(0.20, 0.12);
        double multiSub = sampleAround(0.16, 0.10);
        double delinquency = sampleAround(0.10, 0.10);

        switch (a) {
            case L1A_UNINTERESTED_STABLE -> {
                contact = sampleAround(0.05, 0.05);
                promo = sampleAround(0.10, 0.08);
                outbound = sampleAround(0.08, 0.06);
            }
            case L1B_REGULAR_LOW_USAGE -> contact = sampleAround(0.09, 0.05);
            case L1C_NIGHT_SIMPLE -> { contact = sampleAround(0.08, 0.05); night = sampleAround(0.62, 0.16); }
            case L1D_SINGLE_INQUIRY -> { contact = sampleAround(0.10, 0.06); billing = sampleAround(0.28, 0.12); }
            case H2A_HEAVY_NO_COMPLAINT -> { contact = sampleAround(0.09, 0.05); multiSub = sampleAround(0.28, 0.10); }
            case H2B_HEAVY_QUALITY_SENSITIVE -> { contact = sampleAround(0.28, 0.10); quality = sampleAround(0.62, 0.16); }
            case H2C_HEAVY_NIGHT_FOCUS -> { contact = sampleAround(0.12, 0.06); night = sampleAround(0.72, 0.14); }
            case H2D_HEAVY_BILLING_DISSATISFIED -> { contact = sampleAround(0.26, 0.10); billing = sampleAround(0.60, 0.15); }
            case H2E_HEAVY_MULTI_SUBS -> { contact = sampleAround(0.15, 0.08); multiSub = sampleAround(0.68, 0.15); }
            case P3A_PROMO_RESPONSIVE -> { contact = sampleAround(0.26, 0.09); promo = sampleAround(0.70, 0.15); outbound = sampleAround(0.44, 0.14); }
            case P3B_BILLING_TRACKER -> { contact = sampleAround(0.40, 0.14); billing = sampleAround(0.72, 0.13); }
            case P3C_DOWNGRADE_CONSIDERING -> { contact = sampleAround(0.22, 0.09); retention = sampleAround(0.66, 0.14); billing = sampleAround(0.42, 0.12); }
            case P3D_NEAR_DELINQUENT -> { contact = sampleAround(0.26, 0.10); billing = sampleAround(0.58, 0.14); delinquency = sampleAround(0.74, 0.14); }
            case C4A_CHRONIC_QUALITY_COMPLAINT -> { contact = sampleAround(0.64, 0.16); quality = sampleAround(0.84, 0.12); }
            case C4B_BILLING_DISPUTE -> { contact = sampleAround(0.58, 0.16); billing = sampleAround(0.82, 0.12); delinquency = sampleAround(0.46, 0.16); }
            case C4C_NIGHT_WEEKEND_COMPLAINT -> { contact = sampleAround(0.54, 0.16); quality = sampleAround(0.66, 0.16); night = sampleAround(0.70, 0.16); }
            case C4D_MULTI_CHANNEL_PERSISTENT -> { contact = sampleAround(0.86, 0.12); outbound = sampleAround(0.36, 0.14); billing = sampleAround(0.42, 0.14); quality = sampleAround(0.42, 0.14); }
            case C4E_CHURN_THREAT_COMPLAINT -> { contact = sampleAround(0.50, 0.16); retention = sampleAround(0.82, 0.12); billing = sampleAround(0.48, 0.16); }
            case V5A_LONGTERM_CORE -> { contact = sampleAround(0.08, 0.04); outbound = sampleAround(0.28, 0.10); multiSub = sampleAround(0.36, 0.10); }
            case V5B_VIP_PROMO_RESPONSIVE -> { contact = sampleAround(0.18, 0.07); promo = sampleAround(0.66, 0.14); outbound = sampleAround(0.40, 0.12); multiSub = sampleAround(0.34, 0.10); }
            case V5C_CARE_VIP -> { contact = sampleAround(0.26, 0.08); outbound = sampleAround(0.62, 0.12); quality = sampleAround(0.28, 0.10); }
            case V5D_VIP_QUALITY_SENSITIVE -> { contact = sampleAround(0.24, 0.08); quality = sampleAround(0.60, 0.14); outbound = sampleAround(0.28, 0.10); }
            case D6A_USAGE_DROP_DORMANT -> { contact = sampleAround(0.03, 0.03); retention = sampleAround(0.58, 0.16); }
            case D6B_DISSATISFIED_CHURNED -> { contact = sampleAround(0.38, 0.14); quality = sampleAround(0.74, 0.14); retention = sampleAround(0.74, 0.14); }
            case D6C_DELINQUENT_CHURNED -> { contact = sampleAround(0.32, 0.14); billing = sampleAround(0.72, 0.14); delinquency = sampleAround(0.86, 0.12); retention = sampleAround(0.72, 0.12); }
        }

        if (usageSegment == UsageSegment.HEAVY) multiSub = clamp01(multiSub + 0.06);
        if (usageSegment == UsageSegment.LIGHT) contact = clamp01(contact - 0.02);

        return new LatentTraits(
                clamp01(contact), clamp01(promo), clamp01(billing), clamp01(quality),
                clamp01(retention), clamp01(night), clamp01(outbound), clamp01(multiSub), clamp01(delinquency)
        );
    }

    private double sampleAround(double mean, double sd) {
        return clamp01(mean + r.nextGaussian() * sd);
    }

    private double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static Map<String, Double> defaultRegionWeights() {
        // 대략적인 시도 인구 비중 기반(가중치는 절대값이 아니라 상대값; 합은 자동 정규화됨)
        // 필요하면 여기 숫자를 조정하면 됨.
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("경기", 25.0);
        m.put("서울", 18.0);
        m.put("부산", 6.5);
        m.put("인천", 5.8);
        m.put("대구", 4.4);
        m.put("경남", 6.3);
        m.put("경북", 4.9);
        m.put("충남", 4.1);
        m.put("전북", 3.4);
        m.put("전남", 3.3);
        m.put("충북", 3.1);
        m.put("강원", 2.9);
        m.put("대전", 2.8);
        m.put("광주", 2.7);
        m.put("울산", 2.2);
        m.put("제주", 1.3);
        m.put("세종", 0.7);
        return m;
    }

    private LocalDate randomBirthDate(Random r) {
        // 0~9, 80+ 제외: 10~79만.
        // 통신사 이용자 중심: 30~60대 비중을 조금 높게.
        WeightedPicker<Integer> decade = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>(10, 10),
                new WeightedPicker.Entry<>(20, 14),
                new WeightedPicker.Entry<>(30, 15),
                new WeightedPicker.Entry<>(40, 16),
                new WeightedPicker.Entry<>(50, 17),
                new WeightedPicker.Entry<>(60, 16),
                new WeightedPicker.Entry<>(70, 12)
        ));
        int d = decade.pick(r);
        int age = RandomUtil.nextIntInclusive(r, d, d + 9);
        int yearNow = java.time.LocalDate.now().getYear();
        // 생성 기준연도에 맞춰야 정확하지만, birth_date는 "대략적 분포"만 중요하므로 yearNow 대신 2025 기준으로 고정
        int baseYear = ctx.toYm().getYear();
        int birthYear = baseYear - age;
        int month = RandomUtil.nextIntInclusive(r, 1, 12);
        int day = RandomUtil.nextIntInclusive(r, 1, java.time.YearMonth.of(birthYear, month).lengthOfMonth());
        return LocalDate.of(birthYear, month, day);
    }

    private static String fakeName(long id) {
        String[] family = {"김","이","박","최","정","강","조","윤","장","임","한","오","서","신","권","황","안","송","류","홍"};
        String[] given1 = {"민","서","지","현","준","도","우","예","수","진","하","나","성","태","은","채","경","주","연","호"};
        String[] given2 = {"준","윤","원","아","빈","영","현","훈","서","진","림","희","수","호","아","은","민","찬","규","정"};
        Random rr = new Random(0xBADC0DE ^ id);
        return family[rr.nextInt(family.length)] + given1[rr.nextInt(given1.length)] + given2[rr.nextInt(given2.length)];
    }

    private static String fakePhone(long id) {
        Random rr = new Random(0x1234ABCD ^ id);
        return "010-" + String.format("%04d", rr.nextInt(10000)) + "-" + String.format("%04d", rr.nextInt(10000));
    }

    private static String fakeAddress(String region, long id) {
        // 주소는 실제 매칭보다 "형태"가 중요
        String[] gu = {"중구","서구","남구","북구","동구","강남구","강북구","수성구","해운대구","분당구"};
        Random rr = new Random(0xCAFEF00D ^ (id * 31));
        String zipcode = String.format("%05d", rr.nextInt(100000));
        String road = "가상로" + (rr.nextInt(200)+1) + "길";
        int b = rr.nextInt(200)+1;
        return String.format("%s %s %s %d, %s", region, gu[rr.nextInt(gu.length)], road, b, zipcode);
    }

    private YearMonth decideCancelYm(LocalDateTime createdAt, Archetype archetype, UsageSegment usageSegment, boolean vip, boolean complaint, LatentTraits traits) {
        YearMonth signupYm = YearMonth.from(createdAt);
        YearMonth minCancel = signupYm.plusMonths(1);
        if (minCancel.isAfter(ctx.toYm())) return null;

        double baseProb = 0.015;

        switch (archetype) {
            case P3C_DOWNGRADE_CONSIDERING, C4E_CHURN_THREAT_COMPLAINT -> baseProb *= 2.5;
            case D6B_DISSATISFIED_CHURNED, D6C_DELINQUENT_CHURNED -> baseProb *= 6.0;
            case V5A_LONGTERM_CORE, V5C_CARE_VIP -> baseProb *= 0.6;
            default -> { }
        }
        if (usageSegment == UsageSegment.HEAVY) baseProb *= 0.9;
        if (vip) baseProb *= 0.8;
        if (complaint) baseProb *= 1.1;
        baseProb *= (0.75 + traits.retentionSensitivity * 0.65 + traits.delinquencyRisk * 0.55);

        if (r.nextDouble() >= baseProb) return null;

        List<YearMonth> candidates = new ArrayList<>();
        for (YearMonth ym : ctx.periods()) {
            if (!ym.isBefore(minCancel)) candidates.add(ym);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(r.nextInt(candidates.size()));
    }

    private UsageSegment deriveUsageSegment(Archetype a) {
        return switch (a) {
            case L1A_UNINTERESTED_STABLE, L1B_REGULAR_LOW_USAGE, L1C_NIGHT_SIMPLE, L1D_SINGLE_INQUIRY,
                 D6A_USAGE_DROP_DORMANT -> UsageSegment.LIGHT;

            case P3A_PROMO_RESPONSIVE, P3B_BILLING_TRACKER, P3C_DOWNGRADE_CONSIDERING, P3D_NEAR_DELINQUENT,
                 V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> UsageSegment.NORMAL;

            default -> UsageSegment.HEAVY; // 2/4/5A/6B/6C 등은 heavy 또는 상위 사용 성향으로 둔다
        };
    }

    private boolean deriveVipFlag(Archetype a, LatentTraits traits, Random r) {
        double p = switch (a) {
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> 0.93;
            default -> 0.01 + traits.outboundAffinity * 0.04 + traits.multiSubAffinity * 0.04;
        };
        return r.nextDouble() < clamp01(p);
    }

    private boolean deriveComplaintFlag(Archetype a, LatentTraits traits, Random r) {
        double p = switch (a) {
            case C4A_CHRONIC_QUALITY_COMPLAINT, C4B_BILLING_DISPUTE, C4C_NIGHT_WEEKEND_COMPLAINT,
                 C4D_MULTI_CHANNEL_PERSISTENT, C4E_CHURN_THREAT_COMPLAINT -> 0.88;
            case D6B_DISSATISFIED_CHURNED -> 0.72;
            default -> 0.03 + traits.qualitySensitivity * 0.18 + traits.billingSensitivity * 0.10;
        };
        return r.nextDouble() < clamp01(p);
    }

}
