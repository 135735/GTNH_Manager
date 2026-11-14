package moe.takochan.machineradar.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 分组产能台账：按秒把「组内机器当前配方」的产出/消耗累计到「资源」上。
 *
 * <p>数据来源是每秒一次的客户端采样（{@link RadarClientTicker}），不额外扫世界：
 * 每条记录只做几次乘加，成本 ≈ 机器数 × 常数，和 {@link MachineHistory} 同一量级。
 *
 * <p>时间桶：秒桶 120 个（2 分钟，画秒级曲线），分钟桶 60 个（1 小时，画长窗口）。
 * 每个资源固定 4 个 long 数组，桶按「槽位 = 时间 % 长度」复用，写入前清空旧槽，
 * 所以不需要整体搬移，也没有无限增长。
 *
 * <p>数值是**估算**：按「配方输出 × 已运行时间 / 配方时长」积分，不是 GT 真实的
 * 输出总线计数；对效率统计够用，但不要当仓库对账。
 */
public final class ProductionLedger {

    public static final int WINDOW_SEC = 0; // 最近 2 分钟，1 秒/点
    public static final int WINDOW_MIN = 1; // 最近 15 分钟，1 分钟/点
    public static final int WINDOW_HOUR = 2; // 最近 60 分钟，1 分钟/点

    public static final int SEC_BUCKETS = 120;
    public static final int MIN_BUCKETS = 60;
    private static final int MAX_RESOURCES = 96;

    public static String windowName(int window) {
        switch (window) {
            case WINDOW_MIN:
                return "15分";
            case WINDOW_HOUR:
                return "1时";
            default:
                return "2分";
        }
    }

    /** 一种资源（物品或流体）在某个分组里的产出/消耗 */
    public static final class Flow {

        public final String key;
        public final String name;
        public final boolean fluid;

        final long[] producedSec = new long[SEC_BUCKETS];
        final long[] consumedSec = new long[SEC_BUCKETS];
        final long[] producedMin = new long[MIN_BUCKETS];
        final long[] consumedMin = new long[MIN_BUCKETS];

        public long totalProduced;
        public long totalConsumed;

        private int lastSecSlot = -1;
        private int lastMinSlot = -1;
        private double pendingProduced;
        private double pendingConsumed;

        Flow(String key, String name, boolean fluid) {
            this.key = key;
            this.name = name;
            this.fluid = fluid;
        }
    }

    /** 一个分组的台账 */
    public static final class GroupLedger {

        public final String groupName;
        public final Map<String, Flow> flows = new LinkedHashMap<String, Flow>();

        /** 逐秒 / 逐分钟的合计 EU（耗电为正、发电为负）与完成配方数 */
        final double[] euSec = new double[SEC_BUCKETS];
        final double[] euMin = new double[MIN_BUCKETS];
        final double[] compSec = new double[SEC_BUCKETS];
        final double[] compMin = new double[MIN_BUCKETS];

        public double completionsTotal;

        long lastSeenMs;
        private int lastSecSlot = -1;
        private int lastMinSlot = -1;

        GroupLedger(String groupName) {
            this.groupName = groupName;
        }

        /** 窗口内的平均功率（EU/t） */
        public int avgEut(int window) {
            double sum = sumWindow(euSec, euMin, window);
            int sec = windowSeconds(window);
            return sec <= 0 ? 0 : (int) Math.round(sum / sec);
        }

        /** 窗口内完成的配方数（按运行时间折算） */
        public double completions(int window) {
            return sumWindow(compSec, compMin, window);
        }
    }

    private static final Map<MachineGroup, GroupLedger> LEDGERS = new IdentityHashMap<MachineGroup, GroupLedger>();
    private static long lastTickMs;

    private ProductionLedger() {}

    /** 窗口秒数：秒窗口按"最近 60 秒"给平均功率，分钟/小时按各自长度 */
    public static int windowSeconds(int window) {
        switch (window) {
            case WINDOW_MIN:
                return 15 * 60;
            case WINDOW_HOUR:
                return 60 * 60;
            default:
                return 60;
        }
    }

    private static double sumWindow(double[] sec, double[] min, int window) {
        double total = 0.0D;
        if (window == WINDOW_SEC) {
            int nowSlot = (int) ((System.currentTimeMillis() / 1000L) % SEC_BUCKETS);
            for (int i = 0; i < 60; i++) total += sec[((nowSlot - i) % SEC_BUCKETS + SEC_BUCKETS) % SEC_BUCKETS];
            return total;
        }
        int points = window == WINDOW_MIN ? 15 : MIN_BUCKETS;
        int nowSlot = (int) ((System.currentTimeMillis() / 60_000L) % MIN_BUCKETS);
        for (int i = 0; i < points; i++) total += min[((nowSlot - i) % MIN_BUCKETS + MIN_BUCKETS) % MIN_BUCKETS];
        return total;
    }

    public static void tick() {
        if (!RadarState.enabled) return;
        List<MachineRecord> all = RadarState.snapshot;
        if (all.isEmpty()) return;

        long now = System.currentTimeMillis();
        double dt = lastTickMs == 0L ? 1.0D : (now - lastTickMs) / 1000.0D;
        lastTickMs = now;
        if (dt < 0.2D) dt = 0.2D;
        if (dt > 5.0D) dt = 5.0D;

        int secSlot = (int) ((now / 1000L) % SEC_BUCKETS);
        int minSlot = (int) ((now / 60_000L) % MIN_BUCKETS);

        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.progress < 0) continue;
            MachineGroup group = MachineGroups.groupOf(rec);
            if (group == null) continue;

            GroupLedger ledger = ledgerFor(group);
            rollSlots(ledger, secSlot, minSlot);
            if (rec.eut != 0) {
                double eu = (double) rec.eut * dt;
                ledger.euSec[secSlot] += eu;
                ledger.euMin[minSlot] += eu;
            }

            MachineRecord.RecipeSummary recipe = rec.recipe;
            if (recipe != null && recipe.durationTicks > 0) {
                double rate = 20.0D / recipe.durationTicks;
                double done = dt * rate;
                ledger.compSec[secSlot] += done;
                ledger.compMin[minSlot] += done;
                ledger.completionsTotal += done;
                if (recipe.outItemNames != null) {
                    for (int k = 0; k < recipe.outItemNames.length; k++) {
                        add(ledger, "i:" + recipe.outItemNames[k], recipe.outItemNames[k], false,
                            recipe.outItemAmounts[k] * rate * dt, true, secSlot, minSlot);
                    }
                }
                if (recipe.outFluidNames != null) {
                    for (int k = 0; k < recipe.outFluidNames.length; k++) {
                        add(ledger, "f:" + recipe.outFluidNames[k], recipe.outFluidNames[k], true,
                            recipe.outFluidAmounts[k] * rate * dt, true, secSlot, minSlot);
                    }
                }
                if (recipe.inItemNames != null) {
                    for (int k = 0; k < recipe.inItemNames.length; k++) {
                        add(ledger, "i:" + recipe.inItemNames[k], recipe.inItemNames[k], false,
                            recipe.inItemAmounts[k] * rate * dt, false, secSlot, minSlot);
                    }
                }
                if (recipe.inFluidNames != null) {
                    for (int k = 0; k < recipe.inFluidNames.length; k++) {
                        add(ledger, "f:" + recipe.inFluidNames[k], recipe.inFluidNames[k], true,
                            recipe.inFluidAmounts[k] * rate * dt, false, secSlot, minSlot);
                    }
                }
            } else if (rec.outputName != null && !rec.outputName.isEmpty() && rec.outputAmount > 0
                && rec.maxProgressTicks > 0) {
                // 没有配方数据时的兜底：只知道第一个输出
                double rate = 20.0D / rec.maxProgressTicks;
                double done = dt * rate;
                ledger.compSec[secSlot] += done;
                ledger.compMin[minSlot] += done;
                ledger.completionsTotal += done;
                add(ledger, "i:" + rec.outputName, rec.outputName, false, rec.outputAmount * rate * dt, true, secSlot,
                    minSlot);
            }
        }

        prune();
    }

    private static void rollSlots(GroupLedger ledger, int secSlot, int minSlot) {
        if (ledger.lastSecSlot == secSlot) return;
        ledger.lastSecSlot = secSlot;
        ledger.euSec[secSlot] = 0.0D;
        ledger.compSec[secSlot] = 0.0D;
        for (Flow f : ledger.flows.values()) {
            if (f.lastSecSlot != secSlot) {
                f.producedSec[secSlot] = 0L;
                f.consumedSec[secSlot] = 0L;
                f.lastSecSlot = secSlot;
            }
        }
        if (ledger.lastMinSlot != minSlot) {
            ledger.lastMinSlot = minSlot;
            ledger.euMin[minSlot] = 0.0D;
            ledger.compMin[minSlot] = 0.0D;
            for (Flow f : ledger.flows.values()) {
                if (f.lastMinSlot != minSlot) {
                    f.producedMin[minSlot] = 0L;
                    f.consumedMin[minSlot] = 0L;
                    f.lastMinSlot = minSlot;
                }
            }
        }
    }

    private static void add(GroupLedger ledger, String key, String name, boolean fluid, double amount, boolean produced,
        int secSlot, int minSlot) {
        if (amount <= 0.0D) return;
        Flow f = ledger.flows.get(key);
        if (f == null) {
            if (ledger.flows.size() >= MAX_RESOURCES) return;
            f = new Flow(key, name, fluid);
            ledger.flows.put(key, f);
        }
        if (f.lastSecSlot != secSlot) {
            f.producedSec[secSlot] = 0L;
            f.consumedSec[secSlot] = 0L;
            f.lastSecSlot = secSlot;
        }
        if (f.lastMinSlot != minSlot) {
            f.producedMin[minSlot] = 0L;
            f.consumedMin[minSlot] = 0L;
            f.lastMinSlot = minSlot;
        }

        if (produced) {
            f.pendingProduced += amount;
            long whole = (long) f.pendingProduced;
            if (whole > 0L) {
                f.pendingProduced -= whole;
                f.producedSec[secSlot] += whole;
                f.producedMin[minSlot] += whole;
                f.totalProduced += whole;
            }
        } else {
            f.pendingConsumed += amount;
            long whole = (long) f.pendingConsumed;
            if (whole > 0L) {
                f.pendingConsumed -= whole;
                f.consumedSec[secSlot] += whole;
                f.consumedMin[minSlot] += whole;
                f.totalConsumed += whole;
            }
        }
    }

    private static GroupLedger ledgerFor(MachineGroup group) {
        long now = System.currentTimeMillis();
        GroupLedger ledger = LEDGERS.get(group);
        if (ledger == null) {
            ledger = new GroupLedger(group.name);
            LEDGERS.put(group, ledger);
        } else if (ledger.lastSeenMs != 0L && now - ledger.lastSeenMs > 120_000L) {
            // 停机超过 2 分钟：桶全部清零，避免恢复后把很久以前的数当成当前窗口
            clearBuckets(ledger);
        }
        ledger.lastSeenMs = now;
        return ledger;
    }

    private static void clearBuckets(GroupLedger ledger) {
        for (Flow f : ledger.flows.values()) {
            java.util.Arrays.fill(f.producedSec, 0L);
            java.util.Arrays.fill(f.consumedSec, 0L);
            java.util.Arrays.fill(f.producedMin, 0L);
            java.util.Arrays.fill(f.consumedMin, 0L);
        }
        java.util.Arrays.fill(ledger.euSec, 0.0D);
        java.util.Arrays.fill(ledger.euMin, 0.0D);
        java.util.Arrays.fill(ledger.compSec, 0.0D);
        java.util.Arrays.fill(ledger.compMin, 0.0D);
    }

    private static void prune() {
        if (LEDGERS.isEmpty()) return;
        List<MachineGroup> dead = null;
        for (MachineGroup g : LEDGERS.keySet()) {
            if (!MachineGroups.GROUPS.contains(g)) {
                if (dead == null) dead = new ArrayList<MachineGroup>();
                dead.add(g);
            }
        }
        if (dead != null) {
            for (int i = 0; i < dead.size(); i++) LEDGERS.remove(dead.get(i));
        }
    }

    public static GroupLedger ledger(MachineGroup group) {
        return group == null ? null : LEDGERS.get(group);
    }

    /** 按窗口净产出（产出 - 消耗）排序的资源列表，给 UI 用 */
    public static List<Flow> flows(MachineGroup group, final int window) {
        GroupLedger ledger = ledger(group);
        if (ledger == null || ledger.flows.isEmpty()) return Collections.emptyList();
        List<Flow> out = new ArrayList<Flow>(ledger.flows.values());
        Collections.sort(out, new Comparator<Flow>() {

            @Override
            public int compare(Flow a, Flow b) {
                return Long.compare(net(b, window), net(a, window));
            }
        });
        return out;
    }

    /** 窗口内的产出合计 */
    public static long produced(Flow f, int window) {
        return sum(f.producedSec, f.producedMin, window);
    }

    /** 窗口内的消耗合计 */
    public static long consumed(Flow f, int window) {
        return sum(f.consumedSec, f.consumedMin, window);
    }

    public static long net(Flow f, int window) {
        return produced(f, window) - consumed(f, window);
    }

    /**
     * 曲线的逐点数据（产出或消耗）。秒窗口 = 最近 120 秒的秒桶；分/时窗口 = 分钟桶。
     *
     * @return 长度 = 窗口点数，按时间从旧到新
     */
    public static long[] series(Flow f, int window, boolean producedSeries) {
        if (window == WINDOW_SEC) {
            long[] out = new long[SEC_BUCKETS];
            int nowSlot = (int) ((System.currentTimeMillis() / 1000L) % SEC_BUCKETS);
            for (int i = 0; i < SEC_BUCKETS; i++) {
                int slot = ((nowSlot - SEC_BUCKETS + 1 + i) % SEC_BUCKETS + SEC_BUCKETS) % SEC_BUCKETS;
                out[i] = producedSeries ? f.producedSec[slot] : f.consumedSec[slot];
            }
            return out;
        }
        int points = window == WINDOW_MIN ? 15 : MIN_BUCKETS;
        long[] out = new long[points];
        int nowSlot = (int) ((System.currentTimeMillis() / 60_000L) % MIN_BUCKETS);
        for (int i = 0; i < points; i++) {
            int slot = ((nowSlot - points + 1 + i) % MIN_BUCKETS + MIN_BUCKETS) % MIN_BUCKETS;
            out[i] = producedSeries ? f.producedMin[slot] : f.consumedMin[slot];
        }
        return out;
    }

    private static long sum(long[] sec, long[] min, int window) {
        long total = 0L;
        if (window == WINDOW_SEC) {
            int nowSlot = (int) ((System.currentTimeMillis() / 1000L) % SEC_BUCKETS);
            // 最近 60 秒作为「每分钟」口径
            for (int i = 0; i < 60; i++) {
                int slot = ((nowSlot - i) % SEC_BUCKETS + SEC_BUCKETS) % SEC_BUCKETS;
                total += sec[slot];
            }
            return total;
        }
        int points = window == WINDOW_MIN ? 15 : MIN_BUCKETS;
        int nowSlot = (int) ((System.currentTimeMillis() / 60_000L) % MIN_BUCKETS);
        for (int i = 0; i < points; i++) {
            int slot = ((nowSlot - i) % MIN_BUCKETS + MIN_BUCKETS) % MIN_BUCKETS;
            total += min[slot];
        }
        return total;
    }
}
