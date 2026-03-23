package com.que.telecomdummy.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 생성 결과 CSV의 "정합성" 자동 검증.
 *
 * 검증 포인트:
 * 1) advice: start_at <= end_at == created_at
 * 2) advice: billed/paid_at/overdue 텍스트는 billing 카테고리(=결제/청구 root의 descendant)에서만 허용
 * 3) billing: invoice.overdue_amount > 0 인 (member,month)은 advice에 최소 1건 존재해야 함
 *
 * 실패 시: 에러 카운트 + 대표 샘플 출력
 */
public final class DatasetValidator {
    private DatasetValidator() {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void validateAll(Path outDir, int year, List<Integer> months) throws IOException {
        System.out.println("VALIDATE: start");
        Path masterCategories = outDir.resolve("master").resolve("categories.csv");
        if (!Files.exists(masterCategories)) {
            System.err.println("VALIDATE: missing master/categories.csv -> skip category-based checks");
        }

        CategoryTree tree = Files.exists(masterCategories) ? CategoryTree.load(masterCategories) : null;

        int totalErrors = 0;
        for (int m : months) {
            String ym = String.format("%04d%02d", year, m);
            Path advice = outDir.resolve("advice").resolve("advice_" + ym + ".csv");
            // v2: billing/invoice.csv (single file)
            // legacy: billing/invoice_YYYYMM.csv (monthly split)
            Path invoiceMonthly = outDir.resolve("billing").resolve("invoice_" + ym + ".csv");
            Path invoiceAll = outDir.resolve("billing").resolve("invoice.csv");
            Path invoice = Files.exists(invoiceMonthly) ? invoiceMonthly : invoiceAll;

            if (!Files.exists(advice)) {
                System.err.println("VALIDATE: missing " + advice);
                continue;
            }
            if (!Files.exists(invoice)) {
                System.err.println("VALIDATE: missing " + invoice);
                continue;
            }

            System.out.println("VALIDATE: month=" + ym);

            // overdue member set from invoice (schema-robust)
            Set<Long> overdueMembers = loadOverdueMembers(invoice, ym);

            // advice scan
            AdviceScanResult r = scanAdvice(advice, tree);

            // overdue coverage check
            int miss = 0;
            for (long memberId : overdueMembers) {
                if (!r.membersWithOverdueAdvice.contains(memberId)) {
                    miss++;
                    if (miss <= 5) {
                        System.err.println("VALIDATE[OVERDUE_MISS]: member_id=" + memberId + " month=" + ym);
                    }
                }
            }

            int monthErrors = r.errorCount + miss;
            totalErrors += monthErrors;

            System.out.println("VALIDATE: adviceRows=" + r.rows + ", timeErr=" + r.timeErrors
                    + ", categoryErr=" + r.categoryErrors
                    + ", overdueMembers=" + overdueMembers.size()
                    + ", overdueAdviceMembers=" + r.membersWithOverdueAdvice.size()
                    + ", overdueMiss=" + miss
                    + ", monthErrors=" + monthErrors);
        }

        System.out.println("VALIDATE: done. totalErrors=" + totalErrors);
    }

    private static Set<Long> loadOverdueMembers(Path invoiceCsv, String targetYm) throws IOException {
        // invoice schema can vary between versions.
        // We locate columns by header name, and if invoice.csv is a single file,
        // we filter by base_month == targetYm.
        Set<Long> out = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(invoiceCsv)) {
            String headerLine = br.readLine();
            if (headerLine == null) return out;
            String[] h = splitCsv(headerLine);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < h.length; i++) {
                idx.put(unquote(h[i]).trim(), i);
            }

            int iMember = idx.getOrDefault("member_id", 1);
            int iBase = idx.getOrDefault("base_month", 2);
            int iOverdue = idx.containsKey("overdue_amount") ? idx.get("overdue_amount") : idx.getOrDefault("overdue", -1);
            if (iOverdue < 0) {
                System.err.println("VALIDATE: invoice missing overdue_amount column -> skip overdue coverage check");
                return out;
            }

            boolean isSingleFile = invoiceCsv.getFileName().toString().equalsIgnoreCase("invoice.csv");

            String line;
            while ((line = br.readLine()) != null) {
                String[] c = splitCsv(line);
                if (c.length <= Math.max(iOverdue, Math.max(iMember, iBase))) continue;
                if (isSingleFile) {
                    String bm = unquote(c[iBase]).trim();
                    if (!targetYm.equals(bm)) continue;
                }
                long memberId = parseLong(c[iMember], -1);
                long overdue = parseLong(c[iOverdue], 0);
                if (memberId > 0 && overdue > 0) out.add(memberId);
            }
        }
        return out;
    }

    private static AdviceScanResult scanAdvice(Path adviceCsv, CategoryTree tree) throws IOException {
        int rows = 0;
        int err = 0;
        int timeErr = 0;
        int categoryErr = 0;

        // members that have at least one advice row containing explicit overdue facts
        // (we require "overdue=" to avoid counting normal billed-only messages)
        Set<Long> overdueAdviceMembers = new HashSet<>();

        try (BufferedReader br = Files.newBufferedReader(adviceCsv)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                rows++;
                String[] c = splitCsv(line);
                if (c.length < 12) continue;

                long memberId = parseLong(c[1], -1);
                int categoryId = (int)parseLong(c[3], -1);
                String content = unquote(c[7]);

                // time: start<=end==created
                LocalDateTime start = LocalDateTime.parse(c[8], DT);
                LocalDateTime end = LocalDateTime.parse(c[9], DT);
                LocalDateTime created = LocalDateTime.parse(c[10], DT);
                if (start.isAfter(end) || !end.equals(created)) {
                    timeErr++; err++;
                    if (timeErr <= 3) System.err.println("VALIDATE[TIME]: " + line);
                }

                boolean hasBillingFacts = content.contains("billed=") || content.contains("paid_at=") || content.contains("overdue=");
                if (hasBillingFacts && tree != null && !tree.isUnderBillingRoot(categoryId)) {
                    categoryErr++; err++;
                    if (categoryErr <= 3) System.err.println("VALIDATE[CATEGORY]: " + line);
                }

                // Overdue coverage: accept either
                // - unpaid/overdue notices containing "overdue=",
                // - or "연체 납부 확인" style messages that include paid_at for an overdue invoice.
                if (content.contains("overdue=") || (content.contains("연체") && content.contains("paid_at="))) {
                    overdueAdviceMembers.add(memberId);
                }
            }
        }

        return new AdviceScanResult(rows, err, timeErr, categoryErr, overdueAdviceMembers);
    }

    private record AdviceScanResult(int rows, int errorCount, int timeErrors, int categoryErrors, Set<Long> membersWithOverdueAdvice) {}

    private static String[] splitCsv(String line) {
        // 쉼표 + 따옴표 대응
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }
    private static String unquote(String s) {
        if (s == null) return "";
        String t = s;
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) t = t.substring(1, t.length()-1);
        return t.replace("\"\"", "\"");
    }
    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    /** categories.csv 기반 billing-root descendant 판별 */
    private static final class CategoryTree {
        final Map<Integer, Integer> parentById;
        final Map<Integer, String> nameById;
        final int billingRootId;

        private CategoryTree(Map<Integer, Integer> parentById, Map<Integer, String> nameById, int billingRootId) {
            this.parentById = parentById;
            this.nameById = nameById;
            this.billingRootId = billingRootId;
        }

        static CategoryTree load(Path categoriesCsv) throws IOException {
            Map<Integer, Integer> parent = new HashMap<>();
            Map<Integer, String> name = new HashMap<>();
            // columns: category_id,parent_id,category_name
            try (BufferedReader br = Files.newBufferedReader(categoriesCsv)) {
                br.readLine(); // header
                String line;
                while ((line = br.readLine()) != null) {
                    String[] c = splitCsv(line);
                    if (c.length < 3) continue;
                    int id = (int)parseLong(c[0], -1);
                    String p = c[1];
                    Integer pid = (p == null || p.isBlank()) ? null : (int)parseLong(p, -1);
                    String nm = unquote(c[2]);
                    if (id > 0) {
                        parent.put(id, pid);
                        name.put(id, nm);
                    }
                }
            }

            int billingRoot = -1;
            for (Map.Entry<Integer, String> e : name.entrySet()) {
                if ("결제/청구".equals(e.getValue())) { billingRoot = e.getKey(); break; }
            }
            if (billingRoot == -1) billingRoot = 1;

            return new CategoryTree(parent, name, billingRoot);
        }

        boolean isUnderBillingRoot(int categoryId) {
            if (categoryId <= 0) return false;
            int cur = categoryId;
            int guard = 0;
            while (guard++ < 50) {
                if (cur == billingRootId) return true;
                Integer p = parentById.get(cur);
                if (p == null || p <= 0) return false;
                cur = p;
            }
            return false;
        }
    }
}
