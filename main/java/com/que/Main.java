package com.que;

import com.que.telecomdummy.config.Defaults;
import com.que.telecomdummy.config.GenerationPolicy;
import com.que.telecomdummy.gen.*;
import com.que.telecomdummy.model.GenerationContext;
import com.que.telecomdummy.util.Args;
import com.que.telecomdummy.validation.DatasetValidator;

import java.nio.file.Path;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        int members = a.getInt("--members", -1);
        if (members <= 0) {
            System.err.println("ERROR: --members is required and must be > 0");
            System.exit(2);
        }

        int year = a.getInt("--year", Defaults.DEFAULT_YEAR);
        long seed = a.getLong("--seed", Defaults.DEFAULT_SEED);
        String monthsStr = a.getString("--months", "1-12");
        int usageChunkRows = a.getInt("--usage-chunk-rows", Defaults.DEFAULT_USAGE_CHUNK_ROWS);

        // 옵션: 생성 후 자동 검증
        boolean validate = "true".equalsIgnoreCase(a.getString("--validate", "false"));

        Path outDir = Path.of(a.getString("--out", "./out")).toAbsolutePath().normalize();
        List<Integer> months = Args.parseMonthRange(monthsStr);

        GenerationContext ctx = GenerationContext.builder()
                .outDir(outDir)
                .year(year)
                .months(months)
                .memberCount(members)
                .seed(seed)
                .usageChunkRows(usageChunkRows)
                .build();

        // 정책(워스트 케이스/특수 조건) 로딩
        GenerationPolicy policy = GenerationPolicy.fromArgs(a);

        System.out.println("telecom-dummygen v2");
        System.out.println("outDir=" + ctx.outDir());
        System.out.println("members=" + ctx.memberCount() + ", year=" + ctx.year() + ", months=" + ctx.months());
        System.out.println("seed=" + ctx.seed() + ", usageChunkRows=" + ctx.usageChunkRows());
        System.out.println("policy=" + policy.summarize());

        // 0) Master (참조 무결성 기반)
        MasterDataGenerator master = new MasterDataGenerator(ctx);
        master.generate();

        // 1) Members + consent
        MemberGenerator memberGen = new MemberGenerator(ctx, master);
        memberGen.generate();

        // 2) Subscriptions (C안: 해지 이후 invoice 없음, 해지 달까지 invoice 생성)
        SubscriptionGenerator subGen = new SubscriptionGenerator(ctx, master, memberGen);
        subGen.generate();

        // 3) Billing: invoice, invoice_detail, payment (월별)
        BillingGenerator billingGen = new BillingGenerator(ctx, master, memberGen, subGen, policy);
        billingGen.generate();

        // 4) Usage log (월별 + chunk)
        UsageGenerator usageGen = new UsageGenerator(ctx, memberGen);
        usageGen.generate();

        // 5) Advice log (월별) - billing 사실 기반 + 정책 적용
        AdviceGenerator adviceGen = new AdviceGenerator(ctx, master, memberGen, subGen, billingGen, policy);
        adviceGen.generate();

        if (validate) {
            DatasetValidator.validateAll(outDir, ctx.year(), ctx.months());
        }

        System.out.println("DONE.");
    }
}
