package com.que.telecomdummy.model;

import com.que.telecomdummy.model.Segment.Archetype;
import com.que.telecomdummy.model.Segment.UsageSegment;

import java.time.LocalDateTime;

public record MemberProfile(
        long memberId,
        String name,
        String phone,
        String email,
        String gender,
        String birthDate, // yyyy-MM-dd
        String region,
        String address,
        int householdType,
        LocalDateTime createdAt,
        String status, // ACTIVE/DORMANT/TERMINATED (초기값)
        UsageSegment usageSegment,
        boolean vipFlag,
        boolean complaintFlag,
        int billingCycleDay, // 5/15/25
        Integer cancelMonth, // null or 1..12 (해지 달). cancelMonth 이후 invoice 없음.
        Archetype archetype  // 생성기 내부 잠재 라벨(스키마 변경 없음)
) {}
