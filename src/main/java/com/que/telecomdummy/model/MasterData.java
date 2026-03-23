package com.que.telecomdummy.model;

import java.util.List;
import java.util.Map;

public record MasterData(
        List<AdminUser> admins,
        List<Category> categories,
        List<Promotion> promotions,
        List<Plan> plans,
        Map<String, List<Plan>> plansByType // SUBSCRIPTION_BASE, SUBSCRIPTION_ADDON, ONE_TIME
) {}
