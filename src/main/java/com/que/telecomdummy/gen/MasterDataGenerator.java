package com.que.telecomdummy.gen;

import com.que.telecomdummy.util.BCrypt;
import com.que.telecomdummy.config.GenerationPolicy;
import com.que.telecomdummy.model.*;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.*;

/**
 * 요구사항:
 * - 마스터 3종(plan.csv / categories.csv / promotion.csv)을 고정 경로에서만 관리한다.
 * - 고정 경로에 3개가 모두 존재 + 헤더 일치 => 그대로 로딩
 * - 하나라도 없거나 헤더 불일치 => 생성해서 고정 경로에 저장
 * - 실행 outDir/master 에도 항상 복사본을 떨궈서 확인 가능
 *
 * 추가:
 * - admin 수는 실행 옵션으로 결정
 * - admin/agent 비밀번호는 email 기반으로 계정마다 다르게 생성한 후 해시로 CSV에 저장
 */
public final class MasterDataGenerator {

    /**
     * ✅ 고정 저장 경로
     * 기존 코드의 Windows 절대경로(C:\\...)는 실행 환경에 따라 즉시 깨진다.
     * (리눅스/CI/다른 PC에서 경로가 존재하지 않음)
     *
     * 프로젝트 루트 기준의 상대경로로 고정한다.
     * - repo에 포함된 기본 CSV가 존재하는 위치
     */
    private static final Path FIXED_DIR = Paths.get(
            "src", "main", "java", "com", "que", "telecomdummy", "data"
    );

    // ---------------------------------------------------------------------
    // Admin count / password policy (email-based)
    // ---------------------------------------------------------------------
    // admin 수: -Ddummy.admin.count=5 또는 ENV DUMMY_ADMIN_COUNT=5
    private static final String PROP_ADMIN_COUNT = "dummy.admin.count";
    private static final String ENV_ADMIN_COUNT = "DUMMY_ADMIN_COUNT";

    // marketing 수: -Ddummy.marketing.count=2 또는 ENV DUMMY_MARKETING_COUNT=2
    private static final String PROP_MARKETING_COUNT = "dummy.marketing.count";
    private static final String ENV_MARKETING_COUNT = "DUMMY_MARKETING_COUNT";

    // CS(상담사) 수: -Ddummy.cs.count=50 또는 ENV DUMMY_CS_COUNT=50
    private static final String PROP_CS_COUNT = "dummy.cs.count";
    private static final String ENV_CS_COUNT = "DUMMY_CS_COUNT";

    // email 기반 평문 비밀번호 규칙:
    // 기본: localPart(email) + "@2026!"
    // 필요하면 아래 suffix를 옵션으로 바꿀 수 있음:
    // -Ddummy.admin.pw.suffix=@2026! 또는 ENV DUMMY_ADMIN_PW_SUFFIX=@2026!
    private static final String PROP_PW_SUFFIX = "dummy.admin.pw.suffix";
    private static final String ENV_PW_SUFFIX = "DUMMY_ADMIN_PW_SUFFIX";
    private static final String DEFAULT_PW_SUFFIX = "@2026!";

    private static final String PLAN_HEADER = "product_id,product_name,product_type,product_category,price";
    private static final String CATEGORIES_HEADER = "category_id,parent_id,category_name";
    private static final String PROMOTION_HEADER = "promotion_id,promotion_name,promotion_detail";

    private final GenerationContext ctx;
    private final GenerationPolicy policy;
    private final Random r;

    private MasterData master;

    public MasterDataGenerator(GenerationContext ctx, GenerationPolicy policy) {
        this.ctx = ctx;
        this.policy = (policy == null) ? GenerationPolicy.defaults() : policy;
        this.r = new Random(ctx.seed() ^ 0xA11CE5EEDL);
    }

    public MasterData master() {
        if (master == null) throw new IllegalStateException("MasterDataGenerator.generate() must be called first.");
        return master;
    }

    public void generate() throws Exception {
        Files.createDirectories(FIXED_DIR);

        // 1) 고정 경로의 3종 마스터가 유효하면 로딩, 아니면 생성
        Path fixedPlan = FIXED_DIR.resolve("plan.csv");
        Path fixedCategories = FIXED_DIR.resolve("categories.csv");
        Path fixedPromotion = FIXED_DIR.resolve("promotion.csv");

        List<Plan> plans;
        List<Category> categories;
        List<Promotion> promotions;

        if (isValidCsv(fixedPlan, PLAN_HEADER)
                && isValidCsv(fixedCategories, CATEGORIES_HEADER)
                && isValidCsv(fixedPromotion, PROMOTION_HEADER)) {

            plans = loadPlans(fixedPlan);
            categories = loadCategories(fixedCategories);
            promotions = loadPromotions(fixedPromotion);
            if (!promotionCoverageSufficient(promotions)) {
                promotions = generatePromotions();
                writePromotions(fixedPromotion, promotions);
            }

        } else {
            categories = generateCategories();
            promotions = generatePromotions();
            plans = generatePlans();

            writePlans(fixedPlan, plans);
            writeCategories(fixedCategories, categories);
            writePromotions(fixedPromotion, promotions);
        }

        // admins는 "3개 마스터"에 포함시키지 않음 (매 실행 생성 유지)
        List<AdminUser> admins = generateAdmins();

        Map<String, List<Plan>> byType = indexPlans(plans);
        master = new MasterData(admins, categories, promotions, plans, byType);

        // 2) outDir/master로 복사본 생성 (검증/확인용)
        Path outMaster = ctx.outDir().resolve("master");
        Files.createDirectories(outMaster);

        copy(fixedPlan, outMaster.resolve("plan.csv"));
        copy(fixedCategories, outMaster.resolve("categories.csv"));
        copy(fixedPromotion, outMaster.resolve("promotion.csv"));

        // admin.csv도 기존처럼 out/master에 출력
        writeAdmins(outMaster.resolve("admin.csv"), admins);
    }

    // ---------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------

    private boolean isValidCsv(Path file, String expectedHeader) {
        if (!Files.exists(file)) return false;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return false;
            return header.trim().equalsIgnoreCase(expectedHeader.trim());
        } catch (Exception e) {
            return false;
        }
    }

    private void copy(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------------------------------------------------------------------
    // Loaders
    // ---------------------------------------------------------------------

    private List<Category> loadCategories(Path file) throws IOException {
        List<Category> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> c = parseCsvLine(line);

                int id = Integer.parseInt(c.get(0));
                String parentRaw = c.get(1);
                Integer parent = (parentRaw == null || parentRaw.isBlank()) ? null : Integer.parseInt(parentRaw);
                String name = c.get(2);

                out.add(new Category(id, parent, name));
            }
        }
        return out;
    }

    private List<Promotion> loadPromotions(Path file) throws IOException {
        List<Promotion> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> c = parseCsvLine(line);

                String id = c.get(0);
                String name = c.get(1);
                String detail = c.size() > 2 ? c.get(2) : "";

                out.add(new Promotion(id, name, detail));
            }
        }
        return out;
    }

    private List<Plan> loadPlans(Path file) throws IOException {
        List<Plan> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> c = parseCsvLine(line);

                long id = Long.parseLong(c.get(0));
                String name = c.get(1);
                String type = c.get(2);
                String category = c.get(3);
                long price = Long.parseLong(c.get(4));

                out.add(new Plan(id, name, type, category, price));
            }
        }
        return out;
    }

    /**
     * 최소 CSV 파서: 쉼표 + 쌍따옴표 + "" 이스케이프 지원
     */
    private List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
                continue;
            }

            if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }

            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private boolean promotionCoverageSufficient(List<Promotion> promotions) {
        if (promotions == null || promotions.isEmpty()) return false;
        boolean hasStart = false;
        boolean hasEnd = false;
        for (Promotion p : promotions) {
            int startYm = parseMetaYm(p.promotionDetail(), "start");
            int endYm = parseMetaYm(p.promotionDetail(), "end");
            if (startYm == -1 || endYm == -1) continue;
            int from = Integer.parseInt(DateUtil.ym(ctx.fromYm()));
            int to = Integer.parseInt(DateUtil.ym(ctx.toYm()));
            if (from >= startYm && from <= endYm) hasStart = true;
            if (to >= startYm && to <= endYm) hasEnd = true;
        }
        return hasStart && hasEnd;
    }

    private int parseMetaYm(String detail, String key) {
        if (detail == null) return -1;
        String needle = key + "=";
        int idx = detail.indexOf(needle);
        if (idx < 0) return -1;
        int start = idx + needle.length();
        int end = Math.min(start + 8, detail.length());
        String raw = detail.substring(start, end).replaceAll("[^0-9]", "");
        if (raw.length() < 6) return -1;
        try { return Integer.parseInt(raw.substring(0, 6)); }
        catch (Exception e) { return -1; }
    }

    // ---------------------------------------------------------------------
    // Generators (fallback when fixed files missing)
    // ---------------------------------------------------------------------

    private List<Category> generateCategories() {
        List<Category> list = new ArrayList<>();
        int id = 1;

        int billing = id++;
        int quality = id++;
        int plan = id++;
        int benefit = id++;
        int terminate = id++;
        int etc = id++;

        list.add(new Category(billing, null, "결제/청구"));
        list.add(new Category(quality, null, "품질/장애"));
        list.add(new Category(plan, null, "요금제/상품"));
        list.add(new Category(benefit, null, "혜택/프로모션"));
        list.add(new Category(terminate, null, "가입/해지/변경"));
        list.add(new Category(etc, null, "기타"));

        list.add(new Category(id++, billing, "납부/연체/미납"));
        list.add(new Category(id++, billing, "요금/청구서 문의"));
        list.add(new Category(id++, billing, "환불/정정 요청"));

        list.add(new Category(id++, plan, "요금제 변경"));
        list.add(new Category(id++, plan, "부가서비스"));
        list.add(new Category(id++, plan, "단말/기기 문의"));

        list.add(new Category(id++, benefit, "할인/쿠폰/프로모션"));
        list.add(new Category(id++, benefit, "멤버십/포인트"));
        list.add(new Category(id++, benefit, "제휴카드"));

        list.add(new Category(id++, quality, "통화/문자 장애"));
        list.add(new Category(id++, quality, "데이터/속도"));
        list.add(new Category(id++, quality, "로밍"));

        list.add(new Category(id++, terminate, "해지"));
        list.add(new Category(id++, terminate, "가입"));
        list.add(new Category(id++, terminate, "명의/번호 변경"));

        return list;
    }

    private List<Promotion> generatePromotions() {
        List<Promotion> list = new ArrayList<>();
        // 2025-01 ~ 2026-03 범위를 기본 커버. 이후 범위에서도 일반 프로모션은 무해하다.
        list.add(new Promotion("PROMO_CONTRACT_25P_2025", "선택약정 25% 할인(2025)", "type=DISCOUNT_POLICY|channel=ALL|start=20250101|end=20251231"));
        list.add(new Promotion("PROMO_CARD_AUTOPAY_2025", "제휴카드 자동이체 할인(2025)", "type=CARD_DISCOUNT|channel=ALL|start=20250101|end=20251231"));
        list.add(new Promotion("PROMO_MEMBERSHIP_202501", "유플투쁠 1월 혜택(2025)", "type=MEMBERSHIP|channel=MEMBERSHIP|start=20250101|end=20250131"));
        list.add(new Promotion("PROMO_HOME_BUNDLE_SPRING_2025", "봄 인터넷 결합 혜택(2025)", "type=BENEFIT|channel=ONLINE_HOME|start=20250301|end=20250531"));
        list.add(new Promotion("PROMO_ROAMING_SUMMER_2025", "여름 로밍 데이터 혜택(2025)", "type=EVENT|channel=ROAMING|start=20250601|end=20250831"));
        list.add(new Promotion("PROMO_STORE_BLACK_FRIDAY_2025", "블랙프라이데이 스토어 세일(2025)", "type=SALE|channel=STORE|start=20251101|end=20251130"));
        list.add(new Promotion("PROMO_YEAR_END_RENEWAL_2025", "연말 재약정 혜택(2025)", "type=BENEFIT|channel=ALL|start=20251201|end=20251231"));

        list.add(new Promotion("PROMO_CONTRACT_25P_2026", "선택약정 25% 할인(2026)", "type=DISCOUNT_POLICY|channel=ALL|start=20260101|end=20261231"));
        list.add(new Promotion("PROMO_CARD_AUTOPAY_2026", "제휴카드 자동이체 할인(2026)", "type=CARD_DISCOUNT|channel=ALL|start=20260101|end=20261231"));
        list.add(new Promotion("PROMO_MEMBERSHIP_202601", "유플투쁠 1월 혜택(2026)", "type=MEMBERSHIP|channel=MEMBERSHIP|start=20260101|end=20260131"));
        list.add(new Promotion("PROMO_HOME_BUNDLE_SPRING_2026", "봄 인터넷 결합 혜택(2026)", "type=BENEFIT|channel=ONLINE_HOME|start=20260301|end=20260531"));
        return list;
    }

    private List<Plan> generatePlans() {
        // 기본 랜덤 플랜(고정 파일 없을 때만 사용)
        List<Plan> list = new ArrayList<>();
        long pid = 1000;

        for (int i = 1; i <= 12; i++) {
            long price = 30000 + (i * 5000L);
            list.add(new Plan(pid++, "요금제-" + i, "SUBSCRIPTION", "BASE", price));
        }
        for (int i = 1; i <= 20; i++) {
            long price = 1000 + (i * 500L);
            list.add(new Plan(pid++, "부가서비스-" + i, "SUBSCRIPTION", "ADDON", price));
        }
        for (int i = 1; i <= 60; i++) {
            long price = 9900; // 단건 고정 예시
            list.add(new Plan(pid++, "단건상품-" + i, "ONE_TIME", "ONE_TIME", price));
        }
        return list;
    }

    private List<AdminUser> generateAdmins() {
        List<AdminUser> list = new ArrayList<>();
        LocalDateTime t = LocalDateTime.of(ctx.year(), 1, 1, 0, 0, 0);

        int adminCount = resolveAdminCount();
        int marketingCount = resolveMarketingCount();
        String suffix = resolvePasswordSuffix();

        long id = 1;

        // 1) 최고관리자들 (role=Admin, status=ACTIVE/INACTIVE)
        for (int i = 1; i <= adminCount; i++) {
            String email = "admin" + i + "@corp.test";
            String raw = rawPasswordFromEmail(email, suffix);
            String hash = hashPassword(raw);

            String status = (r.nextDouble() < 0.05) ? "INACTIVE" : "ACTIVE";

            boolean google = (r.nextDouble() < policy.adminGoogleRate);
            list.add(new AdminUser(
                    id++,
                    "관리자" + i,
                    email,
                    String.format("010-0000-%04d", i),
                    google,
                    google ? null : hash,
                    "Admin",
                    status,
                    t, t
            ));
        }

        // 1-b) 마케팅 계정 (role=Marketing)
        for (int i = 1; i <= marketingCount; i++) {
            String email = "marketing" + i + "@corp.test";
            String raw = rawPasswordFromEmail(email, suffix);
            String hash = hashPassword(raw);
            String status = (r.nextDouble() < 0.03) ? "INACTIVE" : "ACTIVE";

            boolean google = (r.nextDouble() < policy.adminGoogleRate);
            list.add(new AdminUser(
                    id++,
                    "마케팅" + i,
                    email,
                    String.format("010-0100-%04d", i),
                    google,
                    google ? null : hash,
                    "Marketing",
                    status,
                    t, t
            ));
        }

        // 2) 상담사 계정 (role=CS) - 개수 옵션 지원
        // -Ddummy.cs.count=50 또는 ENV DUMMY_CS_COUNT=50
        int csCount = resolveCsCount();
        int base = 2000;
        for (int i = 1; i <= csCount; i++) {
            String email = "agent" + i + "@corp.test";
            String raw = rawPasswordFromEmail(email, suffix);
            String hash = hashPassword(raw);
            String status = (r.nextDouble() < 0.03) ? "INACTIVE" : "ACTIVE";

            boolean google = (r.nextDouble() < policy.adminGoogleRate);

            list.add(new AdminUser(
                    id++,
                    "상담사" + i,
                    email,
                    String.format("010-0200-%04d", base + i),
                    google,
                    google ? null : hash,
                    "CS",
                    status,
                    t, t
            ));
        }

        return list;
    }

    private int resolveAdminCount() {
        String v = firstNonBlank(System.getProperty(PROP_ADMIN_COUNT), System.getenv(ENV_ADMIN_COUNT));
        if (v == null) return 1; // 기본 1명
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1) return 1;
            if (n > 1000) return 1000;
            return n;
        } catch (Exception e) {
            return 1;
        }
    }

    private int resolveMarketingCount() {
        String v = firstNonBlank(System.getProperty(PROP_MARKETING_COUNT), System.getenv(ENV_MARKETING_COUNT));
        if (v == null) return 1; // 기본 1명
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) return 0;
            if (n > 1000) return 1000;
            return n;
        } catch (Exception e) {
            return 1;
        }
    }

    private int resolveCsCount() {
        String v = firstNonBlank(System.getProperty(PROP_CS_COUNT), System.getenv(ENV_CS_COUNT));
        if (v == null) return 2; // 기본 2명
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 0) return 0;
            if (n > 50000) return 50000; // 멤버 10만에서도 과도한 관리계정 폭증 방지
            return n;
        } catch (Exception e) {
            return 2;
        }
    }

    private String resolvePasswordSuffix() {
        String s = firstNonBlank(System.getProperty(PROP_PW_SUFFIX), System.getenv(ENV_PW_SUFFIX));
        return (s == null) ? DEFAULT_PW_SUFFIX : s;
    }

    private String rawPasswordFromEmail(String email, String suffix) {
        int at = email.indexOf('@');
        String local = (at > 0) ? email.substring(0, at) : email;
        return local + suffix;
    }

    /**
     * BCrypt 비밀번호 해시.
     * - 형식: $2a$<cost>$... (Spring Security 호환)
     * - 목적: 실서비스 인증/로그인 검증용
     */
    private String hashPassword(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt(10));
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private Map<String, List<Plan>> indexPlans(List<Plan> plans) {
        Map<String, List<Plan>> byType = new HashMap<>();
        byType.put("SUBSCRIPTION_BASE", new ArrayList<>());
        byType.put("SUBSCRIPTION_ADDON", new ArrayList<>());
        byType.put("ONE_TIME", new ArrayList<>());
        for (Plan p : plans) {
            if ("SUBSCRIPTION".equals(p.productType())) {
                if ("BASE".equals(p.productCategory())) {
                    byType.get("SUBSCRIPTION_BASE").add(p);
                } else {
                    // ADDON / ADDON_SERVICE / ADDON_DEVICE 등 확장 카테고리를 모두 ADDON으로 취급
                    byType.get("SUBSCRIPTION_ADDON").add(p);
                }
                continue;
            }
            // 그 외는 ONE_TIME
            byType.get("ONE_TIME").add(p);
        }
        return byType;
    }

    // ---------------------------------------------------------------------
    // Writers
    // ---------------------------------------------------------------------

    private void writeCategories(Path file, List<Category> categories) throws Exception {
        try (CsvWriter w = new CsvWriter(file, List.of("category_id", "parent_id", "category_name"))) {
            for (Category c : categories) {
                w.writeRow(List.of(
                        Integer.toString(c.categoryId()),
                        c.parentId() == null ? "" : Integer.toString(c.parentId()),
                        c.categoryName()
                ));
            }
        }
    }

    private void writePromotions(Path file, List<Promotion> promotions) throws Exception {
        try (CsvWriter w = new CsvWriter(file, List.of("promotion_id", "promotion_name", "promotion_detail"))) {
            for (Promotion p : promotions) {
                w.writeRow(List.of(
                        p.promotionId(),
                        p.promotionName(),
                        p.promotionDetail()
                ));
            }
        }
    }

    private void writeAdmins(Path file, List<AdminUser> admins) throws Exception {
        try (CsvWriter w = new CsvWriter(file, List.of(
                "admin_id", "name", "email", "phone", "google", "password", "role", "status", "created_at", "updated_at"
        ))) {
            for (AdminUser a : admins) {
                w.writeRow(List.of(
                        Long.toString(a.adminId()),
                        a.name(),
                        a.email(),
                        a.phone(),
                        a.google() ? "1" : "0",
                        a.password() == null ? "" : a.password(),
                        a.role(),
                        a.status(),
                        a.createdAt().format(DateUtil.DT),
                        a.updatedAt().format(DateUtil.DT)
                ));
            }
        }
    }

    private void writePlans(Path file, List<Plan> plans) throws Exception {
        try (CsvWriter w = new CsvWriter(file, List.of(
                "product_id", "product_name", "product_type", "product_category", "price"
        ))) {
            for (Plan p : plans) {
                w.writeRow(List.of(
                        Long.toString(p.productId()),
                        p.productName(),
                        p.productType(),
                        p.productCategory(),
                        Long.toString(p.price())
                ));
            }
        }
    }
}