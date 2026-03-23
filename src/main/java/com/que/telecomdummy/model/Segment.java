package com.que.telecomdummy.model;

/**
 * 세그먼트(Archetype)는 "상담/사용/청구/구독" 데이터로부터 역추출 가능하도록
 * 생성기 내부에서만 부여하는 잠재 라벨이다. (CSV 스키마 변경 없이 사용)
 *
 * 기존 UsageSegment(LIGHT/NORMAL/HEAVY)도 유지한다. (하위 생성기 호환용)
 */
public final class Segment {
    private Segment() {}

    /** 사용량 대역(거친 구분) */
    public enum UsageSegment { LIGHT, NORMAL, HEAVY }

    /**
     * 고객 아키타입(세부 세그먼트)
     * - 사용자 정의 1A~6C를 그대로 코드화
     */
    public enum Archetype {
        // 1) 일반 라이트 고객
        L1A_UNINTERESTED_STABLE,
        L1B_REGULAR_LOW_USAGE,
        L1C_NIGHT_SIMPLE,
        L1D_SINGLE_INQUIRY,

        // 2) 데이터 헤비 고객
        H2A_HEAVY_NO_COMPLAINT,
        H2B_HEAVY_QUALITY_SENSITIVE,
        H2C_HEAVY_NIGHT_FOCUS,
        H2D_HEAVY_BILLING_DISSATISFIED,
        H2E_HEAVY_MULTI_SUBS,

        // 3) 가격 민감 고객
        P3A_PROMO_RESPONSIVE,
        P3B_BILLING_TRACKER,
        P3C_DOWNGRADE_CONSIDERING,
        P3D_NEAR_DELINQUENT,

        // 4) 잦은 민원 고객
        C4A_CHRONIC_QUALITY_COMPLAINT,
        C4B_BILLING_DISPUTE,
        C4C_NIGHT_WEEKEND_COMPLAINT,
        C4D_MULTI_CHANNEL_PERSISTENT,
        C4E_CHURN_THREAT_COMPLAINT,

        // 5) 장기 VIP 고객
        V5A_LONGTERM_CORE,
        V5B_VIP_PROMO_RESPONSIVE,
        V5C_CARE_VIP,
        V5D_VIP_QUALITY_SENSITIVE,

        // 6) 휴면/이탈 고객
        D6A_USAGE_DROP_DORMANT,
        D6B_DISSATISFIED_CHURNED,
        D6C_DELINQUENT_CHURNED
    }
}
