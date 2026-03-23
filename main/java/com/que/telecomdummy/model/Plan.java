package com.que.telecomdummy.model;

public record Plan(
        long productId,
        String productName,
        String productType,      // SUBSCRIPTION / ONE_TIME
        String productCategory,  // BASE / ADDON / ONE_TIME
        long price
) {}
