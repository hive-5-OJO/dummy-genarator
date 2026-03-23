package com.que.telecomdummy.validation;

import com.que.telecomdummy.util.DateUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class DatasetValidator {
    private DatasetValidator() {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void validateAll(Path outDir, List<YearMonth> periods) throws IOException {
        System.out.println("VALIDATE: start");
        Path masterCategories = outDir.resolve("master").resolve("categories.csv");
        if (!Files.exists(masterCategories)) {
            System.err.println("VALIDATE: missing master/categories.csv -> skip category-based checks");
        }

        CategoryTree tree = Files.exists(masterCategories) ? CategoryTree.load(masterCategories) : null;
        int totalErrors = 0;
        for (YearMonth ym : periods) {
            String ymKey = DateUtil.ym(ym);
            Path advice = outDir.resolve("advice").resolve("advice_" + ymKey + ".csv");
            Path invoiceMonthly = outDir.resolve("billing").resolve("invoice_" + ymKey + ".csv");
            Path invoiceAll = outDir.resolve("billing").resolve("invoice.csv");
            Path invoice = Files.exists(invoiceMonthly) ? invoiceMonthly : invoiceAll;

            if (!Files.exists(advice) || !Files.exists(invoice)) continue;
            System.out.println("VALIDATE: month=" + ymKey);

            Set<Long> overdueMembers = loadOverdueMembers(invoice, ymKey);
            AdviceScanResult r = scanAdvice(advice, tree);
            int miss = 0;
            for (long memberId : overdueMembers) {
                if (!r.membersWithOverdueAdvice.contains(memberId)) {
                    miss++;
                    if (miss <= 5) System.err.println("VALIDATE[OVERDUE_MISS]: member_id=" + memberId + " month=" + ymKey);
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
        Set<Long> out = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(invoiceCsv)) {
            String headerLine = br.readLine();
            if (headerLine == null) return out;
            String[] h = splitCsv(headerLine);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < h.length; i++) idx.put(unquote(h[i]).trim(), i);
            int iMember = idx.getOrDefault("member_id", 1);
            int iBase = idx.getOrDefault("base_month", 2);
            int iOverdue = idx.containsKey("overdue_amount") ? idx.get("overdue_amount") : idx.getOrDefault("overdue", -1);
            if (iOverdue < 0) return out;
            boolean isSingleFile = invoiceCsv.getFileName().toString().equalsIgnoreCase("invoice.csv");
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = splitCsv(line);
                if (c.length <= Math.max(iOverdue, Math.max(iMember, iBase))) continue;
                if (isSingleFile && !targetYm.equals(unquote(c[iBase]).trim())) continue;
                long memberId = parseLong(c[iMember], -1);
                long overdue = parseLong(c[iOverdue], 0);
                if (memberId > 0 && overdue > 0) out.add(memberId);
            }
        }
        return out;
    }

    private static AdviceScanResult scanAdvice(Path adviceCsv, CategoryTree tree) throws IOException {
        int rows = 0, err = 0, timeErr = 0, categoryErr = 0;
        Set<Long> overdueAdviceMembers = new HashSet<>();
        try (BufferedReader br = Files.newBufferedReader(adviceCsv)) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                rows++;
                String[] c = splitCsv(line);
                if (c.length < 12) continue;
                long memberId = parseLong(c[1], -1);
                int categoryId = (int) parseLong(c[3], -1);
                String content = unquote(c[7]);
                LocalDateTime start = LocalDateTime.parse(c[8], DT);
                LocalDateTime end = LocalDateTime.parse(c[9], DT);
                LocalDateTime created = LocalDateTime.parse(c[10], DT);
                if (start.isAfter(end) || !end.equals(created)) {
                    timeErr++; err++;
                }
                boolean hasBillingFacts = content.contains("billed=") || content.contains("paid_at=") || content.contains("overdue=");
                if (hasBillingFacts && tree != null && !tree.isUnderBillingRoot(categoryId)) {
                    categoryErr++; err++;
                }
                if (content.contains("overdue=") || (content.contains("연체") && content.contains("paid_at="))) {
                    overdueAdviceMembers.add(memberId);
                }
            }
        }
        return new AdviceScanResult(rows, err, timeErr, categoryErr, overdueAdviceMembers);
    }

    private record AdviceScanResult(int rows, int errorCount, int timeErrors, int categoryErrors, Set<Long> membersWithOverdueAdvice) {}

    private static String[] splitCsv(String line) { return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1); }
    private static String unquote(String s) {
        if (s == null) return "";
        String t = s;
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) t = t.substring(1, t.length()-1);
        return t.replace("\"\"", "\"");
    }
    private static long parseLong(String s, long def) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; } }

    private static final class CategoryTree {
        final Map<Integer, Integer> parentById;
        final int billingRootId;
        private CategoryTree(Map<Integer, Integer> parentById, int billingRootId) {
            this.parentById = parentById;
            this.billingRootId = billingRootId;
        }
        static CategoryTree load(Path categoriesCsv) throws IOException {
            Map<Integer, Integer> parent = new HashMap<>();
            Map<Integer, String> name = new HashMap<>();
            try (BufferedReader br = Files.newBufferedReader(categoriesCsv)) {
                br.readLine();
                String line;
                while ((line = br.readLine()) != null) {
                    String[] c = splitCsv(line);
                    if (c.length < 3) continue;
                    int id = (int) parseLong(c[0], -1);
                    Integer pid = (c[1] == null || c[1].isBlank()) ? null : (int) parseLong(c[1], -1);
                    String nm = unquote(c[2]);
                    if (id > 0) {
                        parent.put(id, pid);
                        name.put(id, nm);
                    }
                }
            }
            int billingRoot = name.entrySet().stream()
                    .filter(e -> "결제/청구".equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(1);
            return new CategoryTree(parent, billingRoot);
        }
        boolean isUnderBillingRoot(int categoryId) {
            int cur = categoryId, guard = 0;
            while (cur > 0 && guard++ < 50) {
                if (cur == billingRootId) return true;
                Integer p = parentById.get(cur);
                if (p == null || p <= 0) return false;
                cur = p;
            }
            return false;
        }
    }
}
