package com.que.telecomdummy.model;

public record Category(
        int categoryId,
        Integer parentId,
        String categoryName
) {}
