# telecom-dummygen (v2)

통신사 도메인 더미 데이터 생성기 (1단계 12개 테이블만 생성).

## 생성되는 12개 테이블(=CSV)
- member
- member_consent
- admin
- categories
- promotion
- plan
- subscription_period
- invoice (월별 파일)
- invoice_detail (월별 파일)
- payment (월별 파일)
- data_usage (월별 + chunk 분할)
- advice (월별 파일)

> 분석/feature 테이블(B)은 **완전 제외**.

---

## 핵심 규칙
- **invoice**: 회원 1명당 **월 0~1건** (가입 이전 월은 0건, 해지 이후 월은 0건)
- **해지 정책(C안)**: 해지 이후 invoice 미생성, **해지 달(invoice)** 까지는 생성
- invoice.created_at: 매월 **4일 09:00:00 고정**
- invoice.due_date: **5/15/25** 균등 배치(회원 단위로 고정)
- payment.paid_at: 기본은 1~28일 랜덤(온타임은 due_date 이전/당일), 연체/미납은 확률 모델
- invoice_detail:
  - 월 구독 라인: 최대 3개 (평균 1.6 목표 → 기본요금제 1 + 부가 최대 2개로 달성)
  - 단건 라인: 0~20 (0:50%, 1~3:30%, 4~8:15%, 9~20:5%)
  - product_id는 PK(정규화), product_name_snapshot은 역정규화(스냅샷)
- data_usage: 로그형(하루 1건), 월별 파일로 생성(2월은 28일 고정)
- 상담(advice): 세그먼트 기반(Heavy 고객도 0건 가능), 월별 파일로 출력

---

## 빌드 & 실행
### 1) 빌드
```bash
mvn -q package
```

### 2) 실행
```bash
java -jar target/telecom-dummygen-2.0.0.jar --out ./out --members 100000 --year 2025 --seed 42
```

### 주요 옵션
- `--members` : 생성 인원수(필수)
- `--year` : 생성 기준 연도 (기본 2025)
- `--months` : 생성 월 범위 `1-12` 형태(기본 1-12)
- `--seed` : 난수 시드(기본 42)
- `--usage-chunk-rows` : data_usage CSV를 chunk로 쪼갤 때 파일당 최대 row(기본 2,000,000)

---

## 산출물 폴더 구조
```
out/
  master/
    plan.csv
    categories.csv
    promotion.csv
    admin.csv
  members/
    member.csv
    member_consent.csv
  subscriptions/
    subscription_period.csv
  billing/
    invoice_YYYYMM.csv
    invoice_detail_YYYYMM.csv
    payment_YYYYMM.csv
  usage/
    data_usage_YYYYMM_partNN.csv
  advice/
    advice_YYYYMM.csv
```

---

## 주의
- 10만명 + 12개월 data_usage는 **행 수가 매우 커짐**. `--usage-chunk-rows`로 파일 분할 권장.
