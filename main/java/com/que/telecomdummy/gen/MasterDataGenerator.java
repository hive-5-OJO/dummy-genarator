package com.que.telecomdummy.gen;

import com.que.telecomdummy.util.BCrypt;
import com.que.telecomdummy.model.*;
import com.que.telecomdummy.util.CsvWriter;
import com.que.telecomdummy.util.DateUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 요구사항:
 * - 마스터 3종(plan.csv / categories.csv / promotion.csv)을 관리 및 생성.
 * - 고정 경로에 3개가 모두 존재 + 헤더 일치 => 그대로 로딩
 * - 하나라도 없거나 헤더 불일치 => 생성해서 고정 경로에 저장
 * - 실행 outDir/master 에도 항상 복사본을 출력하여 확인 가능
 *
 * 주요 수정 사항:
 * 1. 실행 환경 독립성 보장: 기존 IDE 종속적인 소스코드 경로(main/java/...)를 제거하고, 
 * 프로젝트 루트 기준의 "./data/master" 디렉토리를 사용하도록 변경 (Docker, CI/CD 배포 오류 해결)
 * 2. 안전한 CSV 파서 도입: 프로모션 상세(promotion_detail) 등에 줄바꿈(\n)이나 
 * 이스케이프된 쌍따옴표가 포함되어 있어도 데이터 파손 없이 정확히 읽어오도록 파서(readNextCsvRecord) 고도화
 */
public final class MasterDataGenerator {

    /**
     * ✅ 고정 저장 경로 (수정됨)
     * 프로젝트 루트 하위의 data/master 폴더를 고정 저장소로 활용합니다.
     */
    private static final Path FIXED_DIR = Paths.get("data", "master");

    // ---------------------------------------------------------------------
    // Admin count / password policy (email-based)
    // ---------------------------------------------------------------------
    // admin 수: -Ddummy.admin.count=5 또는 ENV DUMMY_ADMIN_COUNT=5
    private static final String PROP_ADMIN_COUNT = "dummy.admin.count";
    private static final String ENV_ADMIN_COUNT = "DUMMY_ADMIN_COUNT";

    // marketing 수: -Ddummy.marketing.count=2 또는 ENV DUMMY_MARKETING_COUNT=2
    private static final String PROP_MARKETING_COUNT = "dummy.marketing.count";
    private static final String ENV_MARKETING_COUNT = "DUMMY_MARKETING_COUNT";

    // email 기반 평문 비밀번호 규칙: 기본 localPart(email) + "@2026!"
    private static final String PROP_PW_SUFFIX = "dummy.admin.pw.suffix";
    private static final String ENV_PW_SUFFIX = "DUMMY_ADMIN_PW_SUFFIX";
    private static final String DEFAULT_PW_SUFFIX = "@2026!";

    private static final String PLAN_HEADER = "product_id,product_name,product_type,product_category,price";
    private static final String CATEGORIES_HEADER = "category_id,parent_id,category_name";
    private static final String PROMOTION_HEADER = "promotion_id,promotion_name,promotion_detail";

    private final GenerationContext ctx;
    private final Random r;

    private MasterData master;

    public MasterDataGenerator(GenerationContext ctx) {
        this.ctx = ctx;
        this.r = new Random(ctx.seed() ^ 0xA11CE5EEDL);
    }

    public MasterData master() {
        if (master == null) {
            throw new IllegalStateException("MasterDataGenerator.generate() must be called first.");
        }
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

        } else {
            // 파일이 없거나 헤더가 맞지 않으면 새로 생성
            categories = generateCategories();
            promotions = generatePromotions();
            plans = generatePlans();

            // 생성된 데이터를 고정 경로에 저장
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
            br.readLine(); // 헤더 건너뛰기
            List<String> c;
            while ((c = readNextCsvRecord(br)) != null) {
                if (c.isEmpty() || (c.size() == 1 && c.get(0).isBlank())) continue;

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
            br.readLine(); // 헤더 건너뛰기
            List<String> c;
            while ((c = readNextCsvRecord(br)) != null) {
                if (c.isEmpty() || (c.size() == 1 && c.get(0).isBlank())) continue;

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
            br.readLine(); // 헤더 건너뛰기
            List<String> c;
            while ((c = readNextCsvRecord(br)) != null) {
                if (c.isEmpty() || (c.size() == 1 && c.get(0).isBlank())) continue;

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
     * 쌍따옴표 내부의 쉼표(,) 및 줄바꿈(\n) 이스케이프를 완벽히 지원하는 CSV 레코드 파서
     */
    private List<String> readNextCsvRecord(BufferedReader br) throws IOException {
        String line = br.readLine();
        if (line == null) {
            return null;
        }

        List<String> record = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        while (line != null) {
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);

                if (ch == '"') {
                    // 쌍따옴표 내부에서 쌍따옴표 2개가 연달아 나오면 이스케이프 처리
                    if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++; // 다음 쌍따옴표 건너뛰기
                    } else {
                        // 쌍따옴표 열림/닫힘 상태 토글
                        inQuotes = !inQuotes;
                    }
                } else if (ch == ',' && !inQuotes) {
                    // 따옴표 바깥의 쉼표는 컬럼 구분자
                    record.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(ch);
                }
            }

            if (inQuotes) {
                // 따옴표가 닫히지 않은 채로 줄이 끝나면 다음 줄을 계속 읽어 개행문자와 함께 이어붙임
                sb.append('\n');
                line = br.readLine();
            } else {
                // 정상적으로 레코드 하나가 완료됨
                record.add(sb.toString());
                break;
            }
        }

        return record;
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
        list.add(new Promotion("PROMO_CONTRACT_25P", "선택약정 25% 할인", "type=DISCOUNT_POLICY|channel=ALL|start=20260101|end=20261231"));
        list.add(new Promotion("PROMO_CARD_AUTOPAY", "제휴카드 자동이체 할인", "type=CARD_DISCOUNT|channel=ALL|start=20260101|end=20261231"));
        list.add(new Promotion("PROMO_MEMBERSHIP_01", "유플투쁠 1월 혜택", "type=MEMBERSHIP|channel=MEMBERSHIP|start=20260101|end=20260131"));
        list.add(new Promotion("PROMO_HOME_BUNDLE_SPRING", "봄 인터넷 결합 혜택", "type=BENEFIT|channel=ONLINE_HOME|start=20260301|end=20260531"));
        list.add(new Promotion("PROMO_ROAMING_SUMMER", "여름 로밍 데이터 혜택", "type=EVENT|channel=ROAMING|start=20260601|end=20260831"));
        list.add(new Promotion("PROMO_STORE_BLACK_FRIDAY", "블랙프라이데이 스토어 세일", "type=SALE|channel=STORE|start=20261101|end=20261130"));
        list.add(new Promotion("PROMO_YEAR_END_RENEWAL", "연말 재약정 혜택", "type=BENEFIT|channel=ALL|start=20261201|end=20261231"));
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

            list.add(new AdminUser(
                    id++,
                    "관리자" + i,
                    email,
                    String.format("010-0000-%04d", i),
                    false,              // google=false -> 비번 로그인
                    hash,
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

            list.add(new AdminUser(
                    id++,
                    "마케팅" + i,
                    email,
                    String.format("010-0100-%04d", i),
                    false,
                    hash,
                    "Marketing",
                    status,
                    t, t
            ));
        }

        // 2) 상담사 샘플도 동일 정책 적용 (role=CS)
        String[] agents = {"agentB@corp.test", "agentC@corp.test"};
        int base = 200;
        for (int i = 0; i < agents.length; i++) {
            String email = agents[i];
            String raw = rawPasswordFromEmail(email, suffix);
            String hash = hashPassword(raw);
            String status = (r.nextDouble() < 0.03) ? "INACTIVE" : "ACTIVE";

            list.add(new AdminUser(
                    id++,
                    "상담사" + (char)('B' + i),
                    email,
                    String.format("010-0000-%04d", base + i),
                    false,
                    hash,
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