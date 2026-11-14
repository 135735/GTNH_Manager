package moe.takochan.machineradar.radar;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * 用户自定义的功能分区（组）。成员用「维度:控制器坐标」记录，不依赖每次扫描新建的
 * {@link MachineRecord} 对象，所以拆掉再放回去也能重新对上。
 *
 * <p>只存在于客户端（分组是纯展示/管理数据），持久化在
 * {@code config/machineradar/groups.json}。
 */
public class MachineGroup {

    public String name = "未命名";
    /** RGB，用于列表色条 / 地图点 / 统计 */
    public int color = 0x4FC3F7;
    /** dim:x:y:z */
    public Set<String> machines = new LinkedHashSet<String>();
    /** 分组图标：物品注册名 + damage（GT 机器图标都是带 meta 的物品） */
    public String iconId = "";
    public int iconMeta = 0;

    /**
     * 显式区域（3D 框选定义的），{minX,minY,minZ,maxX,maxY,maxZ}，闭区间方块坐标。
     * null = 没有显式区域，此时分组的范围退回「成员机器 bbox 的并集」。
     * 有这个字段后，分组预览 / 地图 / 列表就不再依赖机器包围盒。
     */
    public int[] region;
    /** region 所在维度；Integer.MIN_VALUE = 没有区域 */
    public int regionDim = Integer.MIN_VALUE;

    public boolean hasRegion() {
        return region != null && region.length == 6 && regionDim != Integer.MIN_VALUE;
    }

    public boolean hasRegion(int dim) {
        return hasRegion() && regionDim == dim;
    }

    public int[] regionCopy() {
        if (!hasRegion()) return null;
        return new int[] { region[0], region[1], region[2], region[3], region[4], region[5] };
    }

    public void setRegion(int dim, int[] box) {
        if (box == null || box.length != 6) return;
        region = new int[] { box[0], box[1], box[2], box[3], box[4], box[5] };
        regionDim = dim;
    }

    public void clearRegion() {
        region = null;
        regionDim = Integer.MIN_VALUE;
    }

    /** 区域体积（方块数）；没有区域返回 0 */
    public long regionVolume() {
        if (!hasRegion()) return 0L;
        return (long) (region[3] - region[0] + 1) * (region[4] - region[1] + 1) * (region[5] - region[2] + 1);
    }

    /** "12×5×30"；没有区域返回空串 */
    public String regionSizeText() {
        if (!hasRegion()) return "";
        return (region[3] - region[0] + 1) + "×" + (region[4] - region[1] + 1) + "×" + (region[5] - region[2] + 1);
    }

    public static String key(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    public static String keyOf(MachineRecord rec) {
        return key(rec.dim, rec.x, rec.y, rec.z);
    }

    public boolean contains(MachineRecord rec) {
        return rec != null && machines.contains(keyOf(rec));
    }

    public boolean add(MachineRecord rec) {
        return rec != null && machines.add(keyOf(rec));
    }

    public boolean remove(MachineRecord rec) {
        return rec != null && machines.remove(keyOf(rec));
    }

    public int size() {
        return machines.size();
    }

    /** 分组图标（没有设置返回 null） */
    public ItemStack iconStack() {
        if (iconId == null || iconId.isEmpty()) return null;
        try {
            Object o = Item.itemRegistry.getObject(iconId);
            if (!(o instanceof Item)) return null;
            return new ItemStack((Item) o, 1, iconMeta);
        } catch (Throwable t) {
            return null;
        }
    }

    public void setIcon(ItemStack stack) {
        if (stack == null) {
            iconId = "";
            iconMeta = 0;
            return;
        }
        try {
            String id = Item.itemRegistry.getNameForObject(stack.getItem());
            if (id == null) return;
            iconId = id;
            iconMeta = stack.getItemDamage();
        } catch (Throwable ignored) {}
    }
}
