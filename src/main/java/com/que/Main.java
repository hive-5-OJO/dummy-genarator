package com.que;

import com.que.telecomdummy.config.Defaults;
import com.que.telecomdummy.config.GenerationPolicy;
import com.que.telecomdummy.gen.*;
import com.que.telecomdummy.model.GenerationContext;
import com.que.telecomdummy.util.Args;
import com.que.telecomdummy.validation.DatasetValidator;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        int members = a.getIntAny(List.of("--members"), -1);
        if (members <= 0) {
            System.err.println("ERROR: --members is required and must be > 0");
            System.exit(2);
        }

        long seed = a.getLongAny(List.of("--seed"), Defaults.DEFAULT_SEED);
        int usageChunkRows = a.getIntAny(List.of("--usage-chunk-rows", "--usageChunkRows"), Defaults.DEFAULT_USAGE_CHUNK_ROWS);
        boolean validate = "true".equalsIgnoreCase(a.getStringAny(List.of("--validate"), "false"));

        int year = a.getIntAny(List.of("--year"), Defaults.DEFAULT_YEAR);
        String monthsStr = a.getStringAny(List.of("--months"), "1-12");
        List<Integer> months = Args.parseMonthRange(monthsStr);

        YearMonth fromYm;
        YearMonth toYm;
        if (a.hasAny(List.of("--from", "--to"))) {
            fromYm = a.has("--from") ? Args.parseYearMonth(a.getString("--from", "")) : YearMonth.of(year, months.get(0));
            toYm = a.has("--to") ? Args.parseYearMonth(a.getString("--to", "")) : YearMonth.of(year, months.get(months.size() - 1));
        } else {
            fromYm = YearMonth.of(year, months.get(0));
            toYm = YearMonth.of(year, months.get(months.size() - 1));
        }

        List<YearMonth> periods = Args.toPeriods(fromYm, toYm);

        Path baseOut = Path.of(a.getStringAny(List.of("--out", "--outDir"), "./out")).toAbsolutePath().normalize();
        boolean flatOut = "true".equalsIgnoreCase(a.getStringAny(List.of("--flat-out", "--flatOut"), "false"));
        String label = sanitize(a.getStringAny(List.of("--label", "--tag"), ""));
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String folder = label.isBlank()
                ? String.format("%s_%s_to_%s_n%d_s%d", ts, fromYm, toYm, members, seed)
                : String.format("%s_%s_%s_to_%s_n%d_s%d", label, ts, fromYm, toYm, members, seed);
        Path outDir = flatOut ? baseOut : baseOut.resolve(folder);

        GenerationContext ctx = GenerationContext.builder()
                .outDir(outDir)
                .year(toYm.getYear())
                .months(months)
                .memberCount(members)
                .seed(seed)
                .usageChunkRows(usageChunkRows)
                .fromYm(fromYm)
                .toYm(toYm)
                .periods(periods)
                .build();

        GenerationPolicy policy = GenerationPolicy.fromArgs(a);

        System.out.println("telecom-dummygen v4");
        System.out.println("outDir=" + ctx.outDir());
        System.out.println("members=" + ctx.memberCount() + ", period=" + ctx.fromYm() + " ~ " + ctx.toYm());
        System.out.println("seed=" + ctx.seed() + ", usageChunkRows=" + ctx.usageChunkRows());
        System.out.println("policy=" + policy.summarize());

        MasterDataGenerator master = new MasterDataGenerator(ctx, policy);
        master.generate();

        MemberGenerator memberGen = new MemberGenerator(ctx, master);
        memberGen.generate();

        SubscriptionGenerator subGen = new SubscriptionGenerator(ctx, master, memberGen);
        subGen.generate();

        BillingGenerator billingGen = new BillingGenerator(ctx, master, memberGen, subGen, policy);
        billingGen.generate();

        UsageGenerator usageGen = new UsageGenerator(ctx, memberGen, policy);
        usageGen.generate();

        AdviceGenerator adviceGen = new AdviceGenerator(ctx, master, memberGen, subGen, billingGen, policy);
        adviceGen.generate();

        if (validate) {
            DatasetValidator.validateAll(outDir, periods);
        }

        System.out.println("DONE.");
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-").replaceAll("(^-|-$)", "");
    }
}
