package moe.takochan.machineradar.radar;

import java.util.Collections;
import java.util.List;


public final class RadarState {

    public static final int LAYER_ALL = 0;
    public static final int LAYER_MULTI = 1;
    public static final int LAYER_SINGLE = 2;

    public static volatile boolean enabled = false;
    public static volatile int layer = LAYER_ALL;
    /** 独立的分组图层：只看分组区域 + 大标签，不画机器本体 */
    public static volatile boolean groupLayer = false;

    public static volatile List<MachineRecord> snapshot = Collections.emptyList();

    public static volatile long lastScanAt = 0L;

    /** 列表里点「追踪」后，世界里高亮的机器（按 dim + 控制器坐标记，因为每次扫描都是新对象） */
    private static volatile int trackedDim = Integer.MIN_VALUE;
    private static volatile int trackedX, trackedY, trackedZ;
    public static volatile long trackedUntil = 0L;

    private RadarState() {}

    public static void track(MachineRecord rec, long durationMs) {
        if (rec == null) return;
        trackedDim = rec.dim;
        trackedX = rec.x;
        trackedY = rec.y;
        trackedZ = rec.z;
        trackedUntil = System.currentTimeMillis() + Math.max(1000L, durationMs);
    }

    public static void clearTracked() {
        trackedDim = Integer.MIN_VALUE;
        trackedUntil = 0L;
    }

    public static boolean hasTracked() {
        return trackedDim != Integer.MIN_VALUE && System.currentTimeMillis() < trackedUntil;
    }

    /** 当前追踪的机器；过期或已不在快照里返回 null */
    public static MachineRecord tracked() {
        if (!hasTracked()) return null;
        int dim = trackedDim;
        int x = trackedX, y = trackedY, z = trackedZ;
        List<MachineRecord> all = snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim == dim && rec.x == x && rec.y == y && rec.z == z) return rec;
        }
        return null;
    }

    public static String layerName(int layer) {
        switch (layer) {
            case LAYER_MULTI:
                return "仅多方块";
            case LAYER_SINGLE:
                return "仅单方块";
            default:
                return "全部";
        }
    }
}
