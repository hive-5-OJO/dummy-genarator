package com.que.telecomdummy.config;

public final class Defaults {
    private Defaults() {}

    public static final int DEFAULT_YEAR = 2025;
    public static final long DEFAULT_SEED = 42L;
    public static final int DEFAULT_USAGE_CHUNK_ROWS = 2_000_000;

    // master data sizes (reasonable defaults)
    public static final int ADMIN_COUNT = 300;          // 상담사/운영자
    public static final int PROMOTION_COUNT = 30;
    public static final int PLAN_SUB_BASE_COUNT = 12;   // 기본 요금제
    public static final int PLAN_SUB_ADDON_COUNT = 20;  // 부가 서비스
    public static final int PLAN_ONE_TIME_COUNT = 60;   // 단건

    // billing probabilities (보수적)
    public static final double PROB_UNPAID = 0.003;            // 미납(전월 포함) baseline
    public static final double PROB_OVERDUE_1M = 0.006;        // 1개월 연체
    public static final double PROB_OVERDUE_2M = 0.0015;       // 2개월 연체
    public static final double PROB_OVERDUE_3P_OR_UNPAID = 0.0005; // 3개월+ or 장기미납
}
