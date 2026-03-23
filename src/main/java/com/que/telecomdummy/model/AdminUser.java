package com.que.telecomdummy.model;

import java.time.LocalDateTime;

public record AdminUser(
        long adminId,
        String name,
        String email,
        String phone,
        boolean google,
        String password,
        String role,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
