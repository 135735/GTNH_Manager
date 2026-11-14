package moe.takochan.machineradar.radar;

import java.util.ArrayList;
import java.util.List;

/**
 * 世界内 3D 框选（手工两角点）状态。
 *
 * <p>纯数据：不碰 OpenGL、不碰 GUI，只记录「维度 + 两个角点」并提供
 * 「某个方块/某台机器是否落在框里」的判定。渲染见
 * {@code client.RadarSelectionRenderer}，归组界面见 {@code client.gui.GuiGroupAssign}。
 *
 * <p>框是**闭区间方块框**：角点就是玩家点中的那个方块本身，所以
 * {@code 12×5×30} 表示含 12*5*30 个方块。
 */
public final class RegionSelection {

    private static boolean active;
    private static boolean hasA;
    private static boolean hasB;
    private static int dim = Integer.MIN_VALUE;
    private static int ax, ay, az;
    private static int bx, by, bz;

    private RegionSelection() {}

    public static boolean isActive() {
        return active;
    }

    public static boolean hasA() {
        return hasA;
    }

    public static boolean hasB() {
        return hasB;
    }

    public static boolean isComplete() {
        return active && hasA && hasB;
    }

    public static int dim() {
        return dim;
    }

    public static int ax() {
        return ax;
    }

    public static int ay() {
        return ay;
    }

    public static int az() {
        return az;
    }

    public static int bx() {
        return bx;
    }

    public static int by() {
        return by;
    }

    public static int bz() {
        return bz;
    }

    /** 开始一个新的框（清掉旧角点） */
    public static void begin(int dimension) {
        active = true;
        hasA = false;
        hasB = false;
        dim = dimension;
        ax = ay = az = 0;
        bx = by = bz = 0;
    }

    /**
     * 设角点 1。换维度会自动重开一个框。
     */
    public static void cornerA(int dimension, int x, int y, int z) {
        if (!active || dim != dimension) begin(dimension);
        ax = x;
        ay = y;
        az = z;
        hasA = true;
        hasB = false;
    }

    /** 设角点 2；还没有角点 1 时退化成设角点 1 */
    public static void cornerB(int dimension, int x, int y, int z) {
        if (!active || dim != dimension) begin(dimension);
        if (!hasA) {
            cornerA(dimension, x, y, z);
            return;
        }
        bx = x;
        by = y;
        bz = z;
        hasB = true;
    }

    /** 关掉工具并丢掉角点（Ctrl+V / 换维度） */
    public static void clear() {
        active = false;
        hasA = false;
        hasB = false;
        dim = Integer.MIN_VALUE;
        ax = ay = az = 0;
        bx = by = bz = 0;
    }

    /** 只丢角点、保留工具开着 */
    public static void resetCorners() {
        hasA = false;
        hasB = false;
    }

    /** @return {minX,minY,minZ,maxX,maxY,maxZ}；没框完返回 null */
    public static int[] box() {
        if (!isComplete()) return null;
        return new int[] { Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by),
            Math.max(az, bz) };
    }

    public static int sizeX() {
        return isComplete() ? Math.abs(ax - bx) + 1 : 0;
    }

    public static int sizeY() {
        return isComplete() ? Math.abs(ay - by) + 1 : 0;
    }

    public static int sizeZ() {
        return isComplete() ? Math.abs(az - bz) + 1 : 0;
    }

    public static long volume() {
        if (!isComplete()) return 0L;
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public static boolean containsBlock(int dimension, int x, int y, int z) {
        int[] b = box();
        if (b == null || dimension != dim) return false;
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    /**
     * 机器是否与选区相交：多方块按整机结构 AABB（没有 bbox 时用控制器那一格），
     * 单方块按它自己那一格。相交即可，不要求控制器在框内。
     */
    public static boolean intersects(MachineRecord rec) {
        if (rec == null) return false;
        int[] b = box();
        if (b == null || rec.dim != dim) return false;
        int minX = rec.multi ? rec.minX : rec.x;
        int minY = rec.multi ? rec.minY : rec.y;
        int minZ = rec.multi ? rec.minZ : rec.z;
        int maxX = rec.multi ? rec.maxX : rec.x;
        int maxY = rec.multi ? rec.maxY : rec.y;
        int maxZ = rec.multi ? rec.maxZ : rec.z;
        return maxX >= b[0] && minX <= b[3] && maxY >= b[1] && minY <= b[4] && maxZ >= b[2] && minZ <= b[5];
    }

    /** 选区内的机器（当前快照，指定维度） */
    public static List<MachineRecord> machinesInside(int dimension) {
        List<MachineRecord> out = new ArrayList<MachineRecord>();
        if (!isComplete() || dimension != dim) return out;
        List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim != dimension) continue;
            if (intersects(rec)) out.add(rec);
        }
        return out;
    }

    public static int countMachines(int dimension) {
        return machinesInside(dimension).size();
    }

    /** "12×5×30" */
    public static String sizeText() {
        return sizeX() + "×" + sizeY() + "×" + sizeZ();
    }
}
