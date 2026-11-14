package moe.takochan.machineradar.client;

import java.util.ArrayList;
import java.util.List;

import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 工厂页统计：把"每帧遍历整个快照"改成**每秒启动一次**的统计任务。
 *
 * <ul>
 * <li>只统计分组（{@code statsGroupedOnly}）时只看已分组的机器，可选只看当前选中的组；</li>
 * <li>全局统计量大时用分帧：每帧只处理 {@link #BUDGET} 台，统计完成前沿用上一次结果；</li>
 * <li>调试信息（耗时/源数量/处理进度）由工厂页的「调试」按钮显示。</li>
 * </ul>
 *
 * 只在客户端 GUI 打开时驱动，不占后台常驻开销。
 */
public final class FactoryStats {

    public static final class Data {

        public int total, running, idle, unformed, single;
        public long genEu;
        public long nanos;
        public String scope = "全局";
    }

    private static final long INTERVAL_GROUPED_MS = 1000L;
    private static final long INTERVAL_GLOBAL_MS = 2500L;
    private static final int BUDGET = 96;

    private static Data current = new Data();
    private static Data acc;
    private static List<MachineRecord> source;
    private static int cursor;
    private static long startNs;
    private static long lastStartMs;
    private static long lastMs;
    private static String scope = "全局";

    private FactoryStats() {}

    public static void tick(int dim, MachineGroup onlyGroup, boolean groupedOnly, boolean incremental) {
        long now = System.currentTimeMillis();
        // 只统计分组时 1 秒一次；全局参数用更大间隔（分帧时拆到每帧预算里）
        long interval = groupedOnly ? INTERVAL_GROUPED_MS : INTERVAL_GLOBAL_MS;
        if (acc == null && now - lastStartMs >= interval) {
            source = buildSource(dim, onlyGroup, groupedOnly);
            acc = new Data();
            acc.scope = scope;
            cursor = 0;
            startNs = System.nanoTime();
            lastStartMs = now;
            if (!incremental) {
                while (acc != null) step(Integer.MAX_VALUE);
            }
        }
        if (acc != null && incremental) step(BUDGET);
    }

    private static void step(int budget) {
        if (acc == null || source == null) return;
        int end = Math.min(source.size(), cursor + budget);
        for (; cursor < end; cursor++) accumulate(acc, source.get(cursor));
        if (cursor >= source.size()) {
            acc.nanos = System.nanoTime() - startNs;
            lastMs = acc.nanos / 1_000_000L;
            current = acc;
            acc = null;
            source = null;
        }
    }

    private static List<MachineRecord> buildSource(int dim, MachineGroup onlyGroup, boolean groupedOnly) {
        List<MachineRecord> all = RadarState.snapshot;
        List<MachineRecord> out = new ArrayList<MachineRecord>(Math.min(all.size(), 1024));
        scope = groupedOnly ? (onlyGroup == null ? "已分组" : onlyGroup.name) : "全局";
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim != dim) continue;
            if (groupedOnly) {
                MachineGroup group = MachineGroups.groupOf(rec);
                if (group == null) continue;
                if (onlyGroup != null && group != onlyGroup) continue;
            }
            out.add(rec);
        }
        return out;
    }

    private static void accumulate(Data data, MachineRecord rec) {
        data.total++;
        byte status = rec.status();
        if (status == MachineRecord.RUNNING) data.running++;
        else if (status == MachineRecord.IDLE) data.idle++;
        else if (status == MachineRecord.UNFORMED) data.unformed++;
        else data.single++;
        if (rec.eut < 0) data.genEu += -rec.eut;
    }

    public static Data data() {
        return current;
    }

    public static boolean sweeping() {
        return acc != null;
    }

    public static int processed() {
        return cursor;
    }

    public static int sourceSize() {
        return source == null ? 0 : source.size();
    }

    public static long lastMs() {
        return lastMs;
    }
}
