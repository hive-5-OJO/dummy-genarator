#!/bin/bash
set -euo pipefail

USER="root"
PASS="high5"
DB="ojo"

DATA_DIR="/var/lib/mysql-files/load"

mysql_exec () {
  MYSQL_PWD="$PASS" mysql -u"$USER" -D "$DB" --local-infile=1 "$@"
}

echo "[0] secure_file_priv"
MYSQL_PWD="$PASS" mysql -u"$USER" -e "SHOW VARIABLES LIKE 'secure_file_priv';"

echo "[1] check data dir"
ls -R "$DATA_DIR"

echo "[2] normalize line endings: remove CR(\\r) from ALL csv"
find "$DATA_DIR" -name "*.csv" -exec sed -i 's/\r$//' {} +

chown -R mysql:mysql "$DATA_DIR" 2>/dev/null || true

echo "[3] truncate tables (FK off)"
mysql_exec <<'EOF'
SET FOREIGN_KEY_CHECKS=0;

TRUNCATE TABLE advice;
TRUNCATE TABLE subscription_period;
TRUNCATE TABLE data_usage;
TRUNCATE TABLE payment;
TRUNCATE TABLE invoice_detail;
TRUNCATE TABLE invoice;
TRUNCATE TABLE member_consent;
TRUNCATE TABLE member;
TRUNCATE TABLE admin;
TRUNCATE TABLE promotion;
TRUNCATE TABLE product;
TRUNCATE TABLE categories;

SET FOREIGN_KEY_CHECKS=1;
EOF

echo "[4] load master"

# categories.csv
# category_id,parent_id,category_name
echo "------------------------------------------"
echo "File : $DATA_DIR/master/categories.csv"
echo "Table: categories"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/master/categories.csv'
INTO TABLE categories
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  category_id,
  @parent_id_raw,
  category_name
)
SET
  parent_id = NULLIF(TRIM(@parent_id_raw), '');
EOF

# plan.csv
# product_id,product_name,product_type,product_category,price
echo "------------------------------------------"
echo "File : $DATA_DIR/master/plan.csv"
echo "Table: product"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/master/plan.csv'
INTO TABLE product
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(product_id, product_name, product_type, product_category, price);
EOF

# promotion.csv
# promotion_id,promotion_name,promotion_detail
echo "------------------------------------------"
echo "File : $DATA_DIR/master/promotion.csv"
echo "Table: promotion"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/master/promotion.csv'
INTO TABLE promotion
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(promotion_id, promotion_name, promotion_detail);
EOF

# admin.csv
# admin_id,name,email,phone,google,password,role,status,created_at,updated_at
echo "------------------------------------------"
echo "File : $DATA_DIR/master/admin.csv"
echo "Table: admin"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/master/admin.csv'
INTO TABLE admin
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  admin_id,
  name,
  email,
  phone,
  @google_raw,
  @password_raw,
  @role_raw,
  @status_raw,
  @created_at_raw,
  @updated_at_raw
)
SET
  google = CASE
             WHEN TRIM(@google_raw) = '1' THEN 1
             ELSE 0
           END,
  password = NULLIF(TRIM(@password_raw), ''),
  role = CASE UPPER(TRIM(@role_raw))
           WHEN 'ADMIN' THEN 'ADMIN'
           WHEN 'CS' THEN 'CS'
           WHEN 'MARKETING' THEN 'MARKETING'
           WHEN 'GUEST' THEN 'GUEST'
           WHEN 'AGENT' THEN 'CS'
           ELSE UPPER(TRIM(@role_raw))
         END,
  status = CASE UPPER(TRIM(@status_raw))
             WHEN 'ACTIVE' THEN 'ACTIVE'
             WHEN 'INACTIVE' THEN 'INACTIVE'
             ELSE UPPER(TRIM(@status_raw))
           END,
  created_at = NULLIF(TRIM(@created_at_raw), ''),
  updated_at = NULLIF(TRIM(@updated_at_raw), '');
EOF

echo "[5] load members"

# member.csv
# member_id,name,phone,email,gender,birth_date,region,address,household_type,created_at,status
echo "------------------------------------------"
echo "File : $DATA_DIR/members/member.csv"
echo "Table: member"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/members/member.csv'
INTO TABLE member
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  member_id,
  name,
  phone,
  email,
  @gender_raw,
  @birth_date_raw,
  region,
  address,
  @household_type_raw,
  @created_at_raw,
  @status_raw
)
SET
  gender = NULLIF(TRIM(@gender_raw), ''),
  birth_date = NULLIF(TRIM(@birth_date_raw), ''),
  household_type = NULLIF(TRIM(@household_type_raw), ''),
  created_at = NULLIF(TRIM(@created_at_raw), ''),
  status = CASE UPPER(TRIM(@status_raw))
             WHEN 'ACTIVE' THEN 'ACTIVE'
             WHEN 'DORMANT' THEN 'DORMANT'
             WHEN 'TERMINATED' THEN 'TERMINATED'
             WHEN 'INACTIVE' THEN 'INACTIVE'
             ELSE UPPER(TRIM(@status_raw))
           END;
EOF

# member_consent.csv
# member_consent_id,member_id,personal_accepted,marketing_accepted,is_converted,accepted_at,expires_at
echo "------------------------------------------"
echo "File : $DATA_DIR/members/member_consent.csv"
echo "Table: member_consent"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/members/member_consent.csv'
INTO TABLE member_consent
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  member_consent_id,
  member_id,
  @personal_raw,
  @marketing_raw,
  @converted_raw,
  @accepted_at_raw,
  @expires_at_raw
)
SET
  personal_accepted = CASE UPPER(TRIM(@personal_raw))
                        WHEN 'Y' THEN 'Y'
                        ELSE 'N'
                      END,
  marketing_accepted = CASE UPPER(TRIM(@marketing_raw))
                         WHEN 'Y' THEN 'Y'
                         ELSE 'N'
                       END,
  is_converted = CASE UPPER(TRIM(@converted_raw))
                   WHEN 'Y' THEN 'Y'
                   ELSE 'N'
                 END,
  accepted_at = NULLIF(TRIM(@accepted_at_raw), ''),
  expires_at = NULLIF(TRIM(@expires_at_raw), '');
EOF

echo "[6] load billing"

# invoice.csv
# invoice_id,member_id,base_month,due_date,billed_amount,overdue_amount,created_at
echo "------------------------------------------"
echo "File : $DATA_DIR/billing/invoice.csv"
echo "Table: invoice"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/billing/invoice.csv'
INTO TABLE invoice
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  invoice_id,
  member_id,
  @base_month_raw,
  @due_date_raw,
  @billed_amount_raw,
  @overdue_amount_raw,
  @created_at_raw
)
SET
  base_month = NULLIF(TRIM(@base_month_raw), ''),
  due_date = NULLIF(TRIM(@due_date_raw), ''),
  billed_amount = NULLIF(TRIM(@billed_amount_raw), ''),
  overdue_amount = NULLIF(TRIM(@overdue_amount_raw), ''),
  created_at = NULLIF(TRIM(@created_at_raw), '');
EOF

# invoice_detail_YYYYMM.csv
# invoice_detail_id,invoice_id,product_id,product_name_snapshot,product_type,quantity,total_price,started_at,end_at
echo "------------------------------------------"
echo "Table: invoice_detail (monthly files)"
invoice_detail_files=$(find "$DATA_DIR/billing" -maxdepth 1 -type f -name 'invoice_detail_*.csv' | sort || true)
if [ -z "$invoice_detail_files" ]; then
  echo "[WARN] no invoice_detail_*.csv found under $DATA_DIR/billing"
fi

while IFS= read -r f; do
  [ -z "$f" ] && continue
  echo "File : $f"

  mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$f'
INTO TABLE invoice_detail
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  invoice_detail_id,
  invoice_id,
  product_id,
  product_name_snapshot,
  product_type,
  @quantity_raw,
  @total_price_raw,
  @started_at_raw,
  @end_at_raw
)
SET
  quantity = NULLIF(TRIM(@quantity_raw), ''),
  total_price = NULLIF(TRIM(@total_price_raw), ''),
  started_at = NULLIF(TRIM(@started_at_raw), ''),
  end_at = NULLIF(TRIM(@end_at_raw), '');
EOF
done <<< "$invoice_detail_files"

# payment.csv
# payment_id,invoice_id,paid_amount,paid_method,paid_at,created_at
echo "------------------------------------------"
echo "File : $DATA_DIR/billing/payment.csv"
echo "Table: payment"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/billing/payment.csv'
INTO TABLE payment
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  payment_id,
  invoice_id,
  @paid_amount_raw,
  @paid_method_raw,
  @paid_at_raw,
  @created_at_raw
)
SET
  paid_amount = NULLIF(TRIM(@paid_amount_raw), ''),
  paid_method = CASE UPPER(TRIM(@paid_method_raw))
                  WHEN '' THEN NULL
                  ELSE UPPER(TRIM(@paid_method_raw))
                END,
  paid_at = NULLIF(TRIM(@paid_at_raw), ''),
  created_at = NULLIF(TRIM(@created_at_raw), '');
EOF

echo "[7] load usage"

# data_usage_YYYYMM.csv
# data_usage_id,member_id,usage_date,usage_time,usage_amount,region,created_at
echo "------------------------------------------"
echo "Table: data_usage (monthly files)"
data_usage_files=$(find "$DATA_DIR/usage" -maxdepth 1 -type f -name 'data_usage_*.csv' | sort || true)
if [ -z "$data_usage_files" ]; then
  echo "[WARN] no data_usage_*.csv found under $DATA_DIR/usage"
fi

while IFS= read -r f; do
  [ -z "$f" ] && continue
  echo "File : $f"

  mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$f'
INTO TABLE data_usage
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  data_usage_id,
  member_id,
  @usage_date_raw,
  @usage_time_raw,
  @usage_amount_raw,
  region,
  @created_at_raw
)
SET
  usage_date = NULLIF(TRIM(@usage_date_raw), ''),
  usage_time = NULLIF(TRIM(@usage_time_raw), ''),
  usage_amount = NULLIF(TRIM(@usage_amount_raw), ''),
  created_at = NULLIF(TRIM(@created_at_raw), '');
EOF
done <<< "$data_usage_files"

echo "[8] load subscriptions"

# subscription_period.csv
# subscription_period_id,product_id,member_id,quantity,total_price,status,started_at,end_at,reason_code
echo "------------------------------------------"
echo "File : $DATA_DIR/subscriptions/subscription_period.csv"
echo "Table: subscription_period"
mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$DATA_DIR/subscriptions/subscription_period.csv'
INTO TABLE subscription_period
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  subscription_period_id,
  product_id,
  member_id,
  @quantity_raw,
  @total_price_raw,
  @status_raw,
  @started_at_raw,
  @end_at_raw,
  @reason_code_raw
)
SET
  quantity = NULLIF(TRIM(@quantity_raw), ''),
  total_price = NULLIF(TRIM(@total_price_raw), ''),
  status = CASE UPPER(TRIM(@status_raw))
             WHEN 'ACTIVE' THEN 'ACTIVE'
             WHEN 'CANCLED' THEN 'CANCLED'
             WHEN 'CANCELLED' THEN 'CANCLED'
             ELSE UPPER(TRIM(@status_raw))
           END,
  started_at = NULLIF(TRIM(@started_at_raw), ''),
  end_at = NULLIF(TRIM(@end_at_raw), ''),
  reason_code = NULLIF(TRIM(@reason_code_raw), '');
EOF

echo "[9] load advice monthly"

# advice_YYYYMM.csv
# advice_id,member_id,admin_id,category_id,promotion_id,direction,channel,advice_content,start_at,end_at,created_at,satisfaction_score
echo "------------------------------------------"
echo "Table: advice (monthly files)"
advice_files=$(find "$DATA_DIR/advice" -maxdepth 1 -type f -name 'advice_*.csv' | sort || true)
if [ -z "$advice_files" ]; then
  echo "[WARN] no advice_*.csv found under $DATA_DIR/advice"
fi

while IFS= read -r f; do
  [ -z "$f" ] && continue
  echo "File : $f"

  mysql_exec <<EOF
SET SESSION sql_mode='';
SET NAMES utf8mb4;

LOAD DATA INFILE '$f'
INTO TABLE advice
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(
  @advice_id_ignored,
  member_id,
  admin_id,
  category_id,
  @promotion_id_raw,
  @direction_raw,
  @channel_raw,
  advice_content,
  @start_at_raw,
  @end_at_raw,
  @created_at_raw,
  @score_raw
)
SET
  promotion_id = NULLIF(TRIM(@promotion_id_raw), ''),
  direction = CASE UPPER(TRIM(@direction_raw))
                WHEN 'IN' THEN 'IN'
                WHEN 'OUT' THEN 'OUT'
                ELSE NULLIF(UPPER(TRIM(@direction_raw)), '')
              END,
  channel = CASE UPPER(TRIM(@channel_raw))
              WHEN 'CALL' THEN 'CALL'
              WHEN 'APP' THEN 'APP'
              WHEN 'SMS' THEN 'SMS'
              ELSE NULLIF(UPPER(TRIM(@channel_raw)), '')
            END,
  start_at = NULLIF(TRIM(@start_at_raw), ''),
  end_at = NULLIF(TRIM(@end_at_raw), ''),
  created_at = NULLIF(TRIM(@created_at_raw), ''),
  satisfaction_score = NULLIF(TRIM(@score_raw), '');
EOF
done <<< "$advice_files"

echo "[10] verify counts"
mysql_exec -e "
SELECT 'categories' t, COUNT(*) c FROM categories
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'promotion', COUNT(*) FROM promotion
UNION ALL SELECT 'admin', COUNT(*) FROM admin
UNION ALL SELECT 'member', COUNT(*) FROM member
UNION ALL SELECT 'member_consent', COUNT(*) FROM member_consent
UNION ALL SELECT 'invoice', COUNT(*) FROM invoice
UNION ALL SELECT 'invoice_detail', COUNT(*) FROM invoice_detail
UNION ALL SELECT 'payment', COUNT(*) FROM payment
UNION ALL SELECT 'data_usage', COUNT(*) FROM data_usage
UNION ALL SELECT 'subscription_period', COUNT(*) FROM subscription_period
UNION ALL SELECT 'advice', COUNT(*) FROM advice;
"

echo "[11] verify generated-domain values"
mysql_exec -e "
SELECT 'admin.google' AS k, CAST(google AS CHAR) AS v, COUNT(*) AS c
FROM admin GROUP BY google
UNION ALL
SELECT 'member.status', status, COUNT(*) FROM member GROUP BY status
UNION ALL
SELECT 'member_consent.personal_accepted', personal_accepted, COUNT(*) FROM member_consent GROUP BY personal_accepted
UNION ALL
SELECT 'member_consent.marketing_accepted', marketing_accepted, COUNT(*) FROM member_consent GROUP BY marketing_accepted
UNION ALL
SELECT 'member_consent.is_converted', is_converted, COUNT(*) FROM member_consent GROUP BY is_converted
UNION ALL
SELECT 'subscription_period.status', status, COUNT(*) FROM subscription_period GROUP BY status
UNION ALL
SELECT 'payment.paid_method', COALESCE(paid_method,'NULL'), COUNT(*) FROM payment GROUP BY paid_method
UNION ALL
SELECT 'advice.direction', COALESCE(direction,'NULL'), COUNT(*) FROM advice GROUP BY direction
UNION ALL
SELECT 'advice.channel', COALESCE(channel,'NULL'), COUNT(*) FROM advice GROUP BY channel
ORDER BY k, v;
"

echo "[12] sample admin rows"
mysql_exec -e "
SELECT admin_id, email, CAST(google AS UNSIGNED) AS google, password, role, status
FROM admin
ORDER BY admin_id
LIMIT 20;
"

echo "[DONE]"