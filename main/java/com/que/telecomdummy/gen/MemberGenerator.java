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
import java.util.*;

/**
 * 멤버 프로필 생성
 * * 주요 수정 사항:
 * 1. 우편번호 정합성 교정: 무효한 '00XXX' 대역을 방지하고 실제 대한민국 
 * 기초구역번호 범위(01000 ~ 63999) 내에서 생성되도록 수정.
 * 2. 결제일(BillingCycle) 현실화: 청구일(4일)과 지나치게 가까웠던 5일 결제를 
 * 21일로 조정(15, 21, 25일)하여 최소 11일 이상의 정상적인 납부 유예 기간 보장.
 */
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
        if (members == null) {
            throw new IllegalStateException("members not generated");
        }
        return members;
    }

    public void generate() throws Exception {
        Path out = ctx.outDir().resolve("members");
        members = new ArrayList<>(ctx.memberCount());

        // 지역 인구 비중 기반 가중치
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

        // Archetype 가중치 분배 (원본 형태 유지)
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

        // 청구서 발행(4일) 대비 현실적인 납부 유예 기간 보장을 위한 결제일 수정
        WeightedPicker<Integer> billingDayPicker = new WeightedPicker<>(List.of(
                new WeightedPicker.Entry<>(15, 1),
                new WeightedPicker.Entry<>(21, 1), // 기존 5일에서 21일로 변경
                new WeightedPicker.Entry<>(25, 1)
        ));

        LocalDate createdStart = LocalDate.of(ctx.year() - 2, 1, 1);
        LocalDate createdEnd = LocalDate.of(ctx.year(), 12, 31);

        for (int i = 1; i <= ctx.memberCount(); i++) {
            long memberId = i;

            String gender = genderPicker.pick(r);
            LocalDate birth = randomBirthDate(r);
            String birthStr = birth.format(DateUtil.D);

            String region = regionPicker.pick(r);
            String address = fakeAddress(region, memberId);

            int householdType = householdPicker.pick(r);

            LocalDate createdDate = DateUtil.randomDate(r, createdStart, createdEnd);
            // 가입시간은 현실적으로 오전/오후에 분포
            int hour = RandomUtil.nextIntInclusive(r, 9, 22);
            LocalDateTime createdAt = createdDate.atTime(hour, 0, 0);

            // 세그먼트(겹칠 수 있음)
            Archetype archetype = archetypePicker.pick(r);
            UsageSegment usageSegment = deriveUsageSegment(archetype);
            boolean vipFlag = deriveVipFlag(archetype, r);
            boolean complaintFlag = deriveComplaintFlag(archetype, r);

            // 해지(연 단위로 약하게). 해지 달까지 invoice 생성.
            Integer cancelMonth = decideCancelMonth(createdAt, archetype, usageSegment, vipFlag, complaintFlag);

            // status는 초기값 (후처리 없이 자연스럽게)
            String status = (cancelMonth != null) ? "TERMINATED" : (r.nextDouble() < 0.03 ? "DORMANT" : "ACTIVE");

            int billingCycleDay = billingDayPicker.pick(r);

            String name = fakeName(memberId);
            String phone = fakePhone(memberId);
            String email = "member" + memberId + "@example.com";

            members.add(new MemberProfile(
                    memberId, name, phone, email, gender, birthStr, region, address,
                    householdType, createdAt, status, usageSegment, vipFlag, complaintFlag, billingCycleDay, cancelMonth, archetype
            ));
        }

        writeMember(out.resolve("member.csv"));
        writeConsent(out.resolve("member_consent.csv"));
    }

    private void writeMember(Path path) throws Exception {
        try (CsvWriter w = new CsvWriter(path, List.of(
                "member_id", "name", "phone", "email", "gender", "birth_date", "region", "address", "household_type", "created_at", "status"
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
        // CRM 분석용: 동의/전환은 ERD가 CHAR(1) Y/N.
        try (CsvWriter w = new CsvWriter(path, List.of(
                "member_consent_id", "member_id", "personal_accepted", "marketing_accepted", "is_converted", "accepted_at", "expires_at"
        ))) {
            long id = 1;
            for (MemberProfile m : members) {
                String personal = (r.nextDouble() < 0.98) ? "Y" : "N";
                String marketing = (r.nextDouble() < 0.45) ? "Y" : "N";
                String converted = ("Y".equals(marketing) && r.nextDouble() < 0.10) ? "Y" : "N";

                LocalDateTime acceptedAt = m.createdAt().plusDays(RandomUtil.nextIntInclusive(r, 0, 7));
                String expires = (r.nextDouble() < 0.7)
                        ? acceptedAt.plusDays(RandomUtil.nextIntInclusive(r, 365, 365 * 3)).format(DateUtil.DT)
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

    private static Map<String, Double> defaultRegionWeights() {
        // 대략적인 시도 인구 비중 기반 (가중치는 상대값)
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
        // 통신사 이용자 중심: 30~60대 비중을 조금 높게
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
        
        // 생성 기준연도에 맞춤
        int baseYear = ctx.year();
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
        String[] gu = {"중구","서구","남구","북구","동구","강남구","강북구","수성구","해운대구","분당구"};
        Random rr = new Random(0xCAFEF00D ^ (id * 31));
        
        // 정합성 확보: 대한민국 우편번호는 01000 ~ 63999 범위 내에 존재함.
        String zipcode = String.format("%05d", RandomUtil.nextIntInclusive(rr, 1000, 63999));
        String road = "가상로" + (rr.nextInt(200)+1) + "길";
        int b = rr.nextInt(200)+1;
        
        return String.format("%s %s %s %d, %s", region, gu[rr.nextInt(gu.length)], road, b, zipcode);
    }

    private Integer decideCancelMonth(LocalDateTime createdAt, Archetype archetype, UsageSegment usageSegment, boolean vip, boolean complaint) {
        // 가입월 이전에는 invoice가 없으므로 해지월도 가입월 이후만 허용.
        int signupMonth = createdAt.getYear() == ctx.year() ? createdAt.getMonthValue() : 1;

        double baseProb = 0.015;

        // archetype 기반 보정(해지/이탈 세그먼트는 높게)
        switch (archetype) {
            case P3C_DOWNGRADE_CONSIDERING, C4E_CHURN_THREAT_COMPLAINT -> baseProb *= 2.5;
            case D6B_DISSATISFIED_CHURNED, D6C_DELINQUENT_CHURNED -> baseProb *= 6.0;
            case V5A_LONGTERM_CORE, V5C_CARE_VIP -> baseProb *= 0.6;
            default -> { /* no-op */ }
        }
        
        // 연 해지 비율(임의, 보수적)
        if (usageSegment == UsageSegment.HEAVY) baseProb *= 0.9;
        if (vip) baseProb *= 0.8;
        if (complaint) baseProb *= 1.1;

        if (r.nextDouble() >= baseProb) return null;
        if (signupMonth >= 12) return 12;
        
        return RandomUtil.nextIntInclusive(r, Math.min(12, signupMonth + 1), 12);
    }

    private UsageSegment deriveUsageSegment(Archetype a) {
        return switch (a) {
            case L1A_UNINTERESTED_STABLE, L1B_REGULAR_LOW_USAGE, L1C_NIGHT_SIMPLE, L1D_SINGLE_INQUIRY,
                 D6A_USAGE_DROP_DORMANT -> UsageSegment.LIGHT;

            case P3A_PROMO_RESPONSIVE, P3B_BILLING_TRACKER, P3C_DOWNGRADE_CONSIDERING, P3D_NEAR_DELINQUENT,
                 V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> UsageSegment.NORMAL;

            default -> UsageSegment.HEAVY;
        };
    }

    private boolean deriveVipFlag(Archetype a, Random r) {
        return switch (a) {
            case V5A_LONGTERM_CORE, V5B_VIP_PROMO_RESPONSIVE, V5C_CARE_VIP, V5D_VIP_QUALITY_SENSITIVE -> true;
            default -> r.nextDouble() < 0.02;
        };
    }

    private boolean deriveComplaintFlag(Archetype a, Random r) {
        return switch (a) {
            case C4A_CHRONIC_QUALITY_COMPLAINT, C4B_BILLING_DISPUTE, C4C_NIGHT_WEEKEND_COMPLAINT,
                 C4D_MULTI_CHANNEL_PERSISTENT, C4E_CHURN_THREAT_COMPLAINT,
                 D6B_DISSATISFIED_CHURNED -> true;
            default -> r.nextDouble() < 0.05;
        };
    }
}