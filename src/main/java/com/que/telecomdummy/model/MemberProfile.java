package com.que.telecomdummy.model;

import com.que.telecomdummy.model.Segment.Archetype;
import com.que.telecomdummy.model.Segment.UsageSegment;

import java.time.LocalDateTime;
import java.time.YearMonth;

public record MemberProfile(
        long memberId,
        String name,
        String phone,
        String email,
        String gender,
        String birthDate,
        String region,
        String address,
        int householdType,
        LocalDateTime createdAt,
        String status,
        UsageSegment usageSegment,
        boolean vipFlag,
        boolean complaintFlag,
        int billingCycleDay,
        YearMonth cancelYm,
        Archetype archetype,
        double contactPropensity,
        double promoAffinity,
        double billingSensitivity,
        double qualitySensitivity,
        double retentionSensitivity,
        double nightBias,
        double outboundAffinity,
        double multiSubAffinity,
        double delinquencyRisk
) {}
