package moe.takochan.machineradar.client;

import java.util.List;

import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 客户端历史采样：每秒把快照里的每台机器记一条（时间 / EU / 产出 / 配方进度 / 是否在跑），
 * 保留最近 {@link #CAPACITY} 条（默认 2 分钟）。
 *
 * <p>这是给列表详情和后续「产量曲线」用的后台数据，不参与渲染；只在客户端线程调用。
 * 单方块机器同样采样，所以小机器的进度/耗电也能看趋势。
 */
public final class MachineHistory {

    public static final int CAPACITY = 120;
    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_MACHINES = 4096;

    /** 统计窗口：秒级看最新一条，分钟级看 60 秒 EMA，小时级看 1 小时 EMA */
    public static final int WINDOW_SEC = 0;
    public static final int WINDOW_MIN = 1;
    public static final int WINDOW_HOUR = 2;

    public static String windowName(int window) {
        switch (window) {
            case WINDOW_MIN:
                return "分钟";
            case WINDOW_HOUR:
                return "小时";
            default:
                return "秒";
        }
    }

    private static final java.util.Map<String, Samples> MAP = new java.util.HashMap<String, Samples>();

    private MachineHistory() {}

    /** 客户端每 20 tick 调一次（见 RadarClientTicker） */
    public static void sample() {
        if (!RadarState.enabled) return;
        List<MachineRecord> snapshot = RadarState.snapshot;
        if (snapshot.isEmpty()) return;
        long now = System.currentTimeMillis();

        for (int i = 0; i < snapshot.size(); i++) {
            MachineRecord rec = snapshot.get(i);
            String key = MachineGroup.keyOf(rec);
            Samples s = MAP.get(key);
            if (s == null) {
                if (MAP.size() >= MAX_MACHINES) continue;
                s = new Samples();
                MAP.put(key, s);
            }
            s.push(now, rec.eut, rec.outputAmount, rec.progress >= 0, rec.progress);
        }

        if (MAP.size() > MAX_MACHINES) MAP.clear();
    }

    public static Samples get(MachineRecord rec) {
        if (rec == null) return null;
        return MAP.get(MachineGroup.keyOf(rec));
    }

    /** 近 1 分钟平均耗电（EU/t，取绝对值平均；发电记负数） */
    public static int avgEut(MachineRecord rec) {
        Samples s = get(rec);
        if (s == null || s.size == 0) return 0;
        long now = System.currentTimeMillis();
        long sum = 0;
        int n = 0;
        for (int i = 0; i < s.size; i++) {
            int idx = s.index(i);
            if (now - s.time[idx] > WINDOW_MS) continue;
            sum += s.eut[idx];
            n++;
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    /** 近 1 分钟平均产出（按记录的产出数量 / 配方时间折算，粗略） */
    public static double avgOutputPerSecond(MachineRecord rec) {
        Samples s = get(rec);
        if (s == null || s.size == 0) return -1.0D;
        long now = System.currentTimeMillis();
        long sum = 0;
        int n = 0;
        for (int i = 0; i < s.size; i++) {
            int idx = s.index(i);
            if (now - s.time[idx] > WINDOW_MS) continue;
            sum += s.output[idx];
            n++;
        }
        if (n == 0 || sum == 0) return -1.0D;
        return (double) sum / n * 20.0D / Math.max(1, rec.maxProgressTicks);
    }

    /** 近 1 分钟里有多少比例的采样是在跑配方 */
    public static double runningFraction(MachineRecord rec) {
        Samples s = get(rec);
        if (s == null || s.size == 0) return -1.0D;
        long now = System.currentTimeMillis();
        int running = 0;
        int n = 0;
        for (int i = 0; i < s.size; i++) {
            int idx = s.index(i);
            if (now - s.time[idx] > WINDOW_MS) continue;
            if ((s.flags[idx] & 1) != 0) running++;
            n++;
        }
        return n == 0 ? -1.0D : (double) running / n;
    }

    // ------------------------------------------------------------------ 统计窗口

    /** 某一窗口下的耗电（EU/t，耗电为正、发电为负） */
    public static int statEut(MachineRecord rec, int window) {
        Samples s = get(rec);
        if (s == null || s.size == 0) return 0;
        if (window == WINDOW_SEC) return s.eut[s.index(s.size - 1)];
        return (int) Math.round(window == WINDOW_MIN ? s.emaEuMin : s.emaEuHour);
    }

    /** 某一窗口下的运行比例 0~1；没有数据 -1 */
    public static double statRunning(MachineRecord rec, int window) {
        Samples s = get(rec);
        if (s == null || s.size == 0) return -1.0D;
        if (window == WINDOW_SEC) return (s.flags[s.index(s.size - 1)] & 1) != 0 ? 1.0D : 0.0D;
        return window == WINDOW_MIN ? s.emaRunMin : s.emaRunHour;
    }

    /** 某一窗口下的产出 / 秒；没有数据 -1 */
    public static double statOutputPerSecond(MachineRecord rec, int window) {
        Samples s = get(rec);
        if (s == null || s.size == 0 || rec.maxProgressTicks <= 0) return -1.0D;
        double amount;
        if (window == WINDOW_SEC) amount = s.output[s.index(s.size - 1)];
        else amount = window == WINDOW_MIN ? s.emaOutMin : s.emaOutHour;
        if (amount <= 0.0D) return -1.0D;
        return amount * 20.0D / rec.maxProgressTicks;
    }

    /** 一组机器在某个窗口下的合计（全局预览 / 分组预览都用它） */
    public static final class WindowStats {

        public int devices, runningNo, idleNo, unformedNo, singleNo;
        /** 窗口运行比例 0~1；-1 = 没数据 */
        public double runRatio = -1.0D;
        /** 合计 EU/t（耗电为正、发电为负） */
        public long eut;
        /** 合计产出 / 秒 */
        public double outputPerSecond;
    }

    public static WindowStats windowStats(List<MachineRecord> recs, int window) {
        WindowStats w = new WindowStats();
        if (recs == null) return w;
        double runSum = 0.0D;
        int runN = 0;
        for (int i = 0; i < recs.size(); i++) {
            MachineRecord rec = recs.get(i);
            if (rec == null) continue;
            w.devices++;
            byte status = rec.status();
            if (status == MachineRecord.RUNNING) w.runningNo++;
            else if (status == MachineRecord.IDLE) w.idleNo++;
            else if (status == MachineRecord.UNFORMED) w.unformedNo++;
            else w.singleNo++;

            w.eut += statEut(rec, window);
            double out = statOutputPerSecond(rec, window);
            if (out > 0.0D) w.outputPerSecond += out;
            double run = statRunning(rec, window);
            if (run >= 0.0D) {
                runSum += run;
                runN++;
            }
        }
        w.runRatio = runN == 0 ? -1.0D : runSum / runN;
        return w;
    }

    /** 分组在当前维度的窗口统计 */
    public static WindowStats groupWindowStats(MachineGroup group, int dim, int window) {
        return windowStats(membersOf(group, dim), window);
    }

    /** 全局（当前维度所有机器）的窗口统计 */
    public static WindowStats globalWindowStats(int dim, int window) {
        return windowStats(membersInDim(dim), window);
    }

    private static List<MachineRecord> membersInDim(int dim) {
        List<MachineRecord> out = new java.util.ArrayList<MachineRecord>();
        List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim == dim) out.add(rec);
        }
        return out;
    }

    private static List<MachineRecord> membersOf(MachineGroup group, int dim) {
        List<MachineRecord> out = new java.util.ArrayList<MachineRecord>();
        if (group == null) return out;
        List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim == dim && group.contains(rec)) out.add(rec);
        }
        return out;
    }


    /**
     * 分组级的聚合轨迹：把组内每台机器的采样**按末端对齐**后逐点求和/求平均。
     *
     * <p>采样是同一个 tick 里对整份快照一起压的，所以只要都用"最新一条"对齐，
     * 时间轴就是一致的。成员采样长度不同（中途进组/新扫描出来）时取最短的公共长度。
     */
    public static final class GroupTrace {

        /** 有采样的成员数 */
        public int members;
        /** 对齐后的采样点数 */
        public int points;
        /** 每个点的运行比例 0~1（运行台数 / 成员数） */
        public double[] activity;
        /** 每个点的合计 EU/t（耗电为正、发电为负） */
        public long[] eut;
        /** 近 1 分钟运行比例；没有数据 -1 */
        public double runningFraction = -1.0D;
        /** 合计平均 EU/t */
        public int avgEut;
        /** 合计平均产出 / 秒 */
        public double outputPerSecond;

        public boolean valid() {
            return points > 0 && activity != null && eut != null;
        }
    }

    /** 分组聚合（只看当前维度、组内在线且有历史采样的机器） */
    public static GroupTrace trace(MachineGroup group, int dim) {
        if (group == null) return new GroupTrace();
        return traceMembers(membersOf(group, dim));
    }

    /** 全局聚合（当前维度所有有采样的机器）——列表「分析 · 全局」的效率曲线 */
    public static GroupTrace traceAll(int dim) {
        return traceMembers(membersInDim(dim));
    }

    private static GroupTrace traceMembers(List<MachineRecord> candidates) {
        GroupTrace t = new GroupTrace();

        List<MachineRecord> members = new java.util.ArrayList<MachineRecord>();
        int points = Integer.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            MachineRecord rec = candidates.get(i);
            Samples s = get(rec);
            if (s == null || s.size == 0) continue;
            members.add(rec);
            if (s.size < points) points = s.size;
        }
        if (members.isEmpty() || points == Integer.MAX_VALUE || points <= 0) return t;

        t.members = members.size();
        t.points = points;
        t.activity = new double[points];
        t.eut = new long[points];
        int[] running = new int[points];

        long now = System.currentTimeMillis();
        int runningTotal = 0;
        int windowSamples = 0;
        double outSum = 0.0D;

        for (int m = 0; m < members.size(); m++) {
            MachineRecord rec = members.get(m);
            Samples s = get(rec);
            if (s == null) continue;
            int base = s.size - points;
            for (int i = 0; i < points; i++) {
                int idx = s.index(base + i);
                if ((s.flags[idx] & 1) != 0) running[i]++;
                t.eut[i] += s.eut[idx];
                if (now - s.time[idx] <= WINDOW_MS) {
                    if ((s.flags[idx] & 1) != 0) runningTotal++;
                    windowSamples++;
                }
            }
            double out = avgOutputPerSecond(rec);
            if (out > 0.0D) outSum += out;
        }

        long euSum = 0L;
        for (int i = 0; i < points; i++) {
            t.activity[i] = (double) running[i] / (double) members.size();
            euSum += t.eut[i];
        }
        t.avgEut = (int) (euSum / points);
        t.runningFraction = windowSamples == 0 ? -1.0D : (double) runningTotal / (double) windowSamples;
        t.outputPerSecond = outSum;
        return t;
    }

    public static final class Samples {

        private final long[] time = new long[CAPACITY];
        private final int[] eut = new int[CAPACITY];
        private final long[] output = new long[CAPACITY];
        private final short[] progress = new short[CAPACITY];
        private final byte[] flags = new byte[CAPACITY];
        private int head;
        private int size;

        /** 分钟 / 小时窗口用的指数滑动平均（按时间常数衰减，内存只有 6 个 double） */
        private double emaRunMin;
        private double emaRunHour;
        private double emaEuMin;
        private double emaEuHour;
        private double emaOutMin;
        private double emaOutHour;
        private long lastMs;

        private Samples() {}

        private void push(long t, int eu, long out, boolean running, int prog) {
            time[head] = t;
            eut[head] = eu;
            output[head] = out;
            progress[head] = (short) Math.max(-1, Math.min(1000, prog));
            flags[head] = (byte) (running ? 1 : 0);
            head = (head + 1) % CAPACITY;
            if (size < CAPACITY) size++;

            double run = running ? 1.0D : 0.0D;
            if (lastMs <= 0L) {
                emaRunMin = emaRunHour = run;
                emaEuMin = emaEuHour = eu;
                emaOutMin = emaOutHour = out;
            } else if (t > lastMs) {
                // 卡顿/暂停时 dt 可能很大，夹一下避免 EMA 直接跳到当前值
                double dt = Math.min(600.0D, (t - lastMs) / 1000.0D);
                double aMin = 1.0D - Math.exp(-dt / 60.0D);
                double aHour = 1.0D - Math.exp(-dt / 3600.0D);
                emaRunMin += aMin * (run - emaRunMin);
                emaRunHour += aHour * (run - emaRunHour);
                emaEuMin += aMin * (eu - emaEuMin);
                emaEuHour += aHour * (eu - emaEuHour);
                emaOutMin += aMin * (out - emaOutMin);
                emaOutHour += aHour * (out - emaOutHour);
            }
            lastMs = t;
        }

        /** i = 0 表示最老的一条 */
        public int index(int i) {
            int start = size == CAPACITY ? head : 0;
            return (start + i) % CAPACITY;
        }

        public int size() {
            return size;
        }

        public long time(int i) {
            return time[index(i)];
        }

        public int eut(int i) {
            return eut[index(i)];
        }

        /** 0~1000；-1 = 当时没有配方 */
        public int progress(int i) {
            return progress[index(i)];
        }

        public byte flags(int i) {
            return flags[index(i)];
        }
    }
}
