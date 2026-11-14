package moe.takochan.machineradar.radar;

import net.minecraft.item.ItemStack;

public class MachineRecord {

    public static final byte UNFORMED = 0;
    public static final byte IDLE = 1;
    public static final byte RUNNING = 2;
    public static final byte SINGLE = 3;

    public int x, y, z, dim;
    public int id;
    public String name = "?";
    public String metaName = "?";
    public boolean multi;
    public boolean assembled;
    public byte tier = -1;

    /** 0~1000；-1 表示没有配方在跑 */
    public int progress = -1;
    /** 当前配方已跑 / 总共需要多少 tick */
    public int progressTicks;
    public int maxProgressTicks;
    /** 当前耗电（EU/t，负=发电） */
    public int eut;
    /** 能源仓合计上限（V × A） */
    public long maxEuInput;
    public int maxAmps;

    /** 机器本身的物品形态（用于世界内图标） */
    public ItemStack icon;

    /** 当前配方产出（第一个） */
    public String outputName = "";
    public long outputAmount;
    public ItemStack outputIcon;

    /** 维护仓缺几项，0=正常 */
    public int maintenanceMissing;
    public boolean hasMaintenance;

    public int minX, minY, minZ, maxX, maxY, maxZ;
    public boolean bboxExact;
    public String bboxError = "";
    /** 由 GT 结构校验走出来的精确方块集合，null = 没算出来 */
    public int[][] blocks;
    /** 结构方块数（0 = 没算出来） */
    public int blockCount;

    /** GT 的"允许工作"状态（软锤开关）；false = 被远程/手动停机 */
    public boolean allowed = true;

    public HatchRef[] hatches = new HatchRef[0];

    /** 当前配方摘要；null = 没在跑或读不到 */
    public RecipeSummary recipe;

    /** 存储机器（量子箱 / 量子缸）的库存摘要；null = 不是存储机器 */
    public StorageInfo storage;

    public boolean hasRecipe() {
        return recipe != null && recipe.durationTicks > 0;
    }

    public boolean hasStorage() {
        return storage != null && (storage.itemCount > 0 || storage.fluidAmount > 0);
    }

    /**
     * 配方摘要：最多保留 {@link #MAX_STACKS} 种输入/输出，避免快照无限膨胀。
     * 只存显示名 + 数量，够列表/详情/台账用；不做精确 NBT 匹配。
     */
    public static final class RecipeSummary {

        public static final int MAX_STACKS = 6;

        public String[] inItemNames, outItemNames, inFluidNames, outFluidNames;
        public long[] inItemAmounts, outItemAmounts, inFluidAmounts, outFluidAmounts;
        /** 配方总时长（tick）与耗电（EU/t） */
        public int durationTicks;
        public int eut;

        public boolean hasInputs() {
            return (inItemNames != null && inItemNames.length > 0) || (inFluidNames != null && inFluidNames.length > 0);
        }

        public boolean hasOutputs() {
            return (outItemNames != null && outItemNames.length > 0)
                || (outFluidNames != null && outFluidNames.length > 0);
        }
    }

    /** 存储机器（量子箱/缸）读到的内容 */
    public static final class StorageInfo {

        public boolean chest;
        public boolean tank;

        public String itemName;
        public long itemCount;
        public long itemCapacity;

        public String fluidName;
        public long fluidAmount;
        public long fluidCapacity;
    }

    public byte status() {
        if (!multi) return SINGLE;
        if (!assembled) return UNFORMED;
        return progress >= 0 ? RUNNING : IDLE;
    }

    /** 统一的显示状态：停机优先于运行/待机 */
    public String statusText() {
        if (!allowed) return "已停机";
        switch (status()) {
            case RUNNING:
                return "运行中";
            case IDLE:
                return "待机";
            case UNFORMED:
                return "未成型";
            default:
                return "单方块";
        }
    }

    public int colorOf() {
        if (!allowed) return 0x9E9E9E;
        switch (status()) {
            case RUNNING:
                return 0x66FF66;
            case IDLE:
                return 0xFFD54F;
            case UNFORMED:
                return 0xFF5252;
            default:
                return HatchRef.tierColor(tier);
        }
    }

    public int hatchCount(int kind) {
        int n = 0;
        for (HatchRef h : hatches) if (h.kind == kind) n++;
        return n;
    }

    /** 某一类舱室的合计数量（物品=个数，流体=mB） */
    public long hatchAmount(int kind) {
        long sum = 0L;
        for (int i = 0; i < hatches.length; i++) {
            if (hatches[i].kind == kind) sum += hatches[i].amount;
        }
        return sum;
    }

    /** 某一类舱室的合计容量（流体 mB，物品仓为 0） */
    public long hatchCapacity(int kind) {
        long sum = 0L;
        for (int i = 0; i < hatches.length; i++) {
            HatchRef h = hatches[i];
            if (h.kind == kind && h.capacity > 0) sum += h.capacity;
        }
        return sum;
    }

    public int hatchUsedSlots(int kind) {
        int sum = 0;
        for (int i = 0; i < hatches.length; i++) {
            if (hatches[i].kind == kind) sum += hatches[i].usedSlots;
        }
        return sum;
    }

    public int hatchTotalSlots(int kind) {
        int sum = 0;
        for (int i = 0; i < hatches.length; i++) {
            if (hatches[i].kind == kind) sum += hatches[i].totalSlots;
        }
        return sum;
    }

    /** 单个配方耗时（秒），未知返回 -1 */
    public double recipeSeconds() {
        return maxProgressTicks > 0 ? maxProgressTicks / 20.0D : -1.0D;
    }

    /** 按当前速率折算的每秒产出，未知返回 -1 */
    public double outputPerSecond() {
        if (outputAmount <= 0 || maxProgressTicks <= 0) return -1.0D;
        return outputAmount * 20.0D / maxProgressTicks;
    }
}