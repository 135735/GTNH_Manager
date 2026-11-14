package moe.takochan.machineradar.radar;

import net.minecraft.item.ItemStack;

public class HatchRef {

    public static final int ITEM_IN = 0;
    public static final int ITEM_OUT = 1;
    public static final int FLUID_IN = 2;
    public static final int FLUID_OUT = 3;
    public static final int ENERGY_IN = 4;
    public static final int ENERGY_OUT = 5;
    public static final int MAINTENANCE = 6;
    public static final int MUFFLER = 7;
    public static final int ME = 8;
    public static final int COKE = 9;
    public static final int OTHER = 10;

    public final int x, y, z, kind;

    public byte tier = -1;
    public long amount;
    public long capacity = -1;
    public String content = "";
    public int usedSlots;
    public int totalSlots;
    /** 朝向（ForgeDirection ordinal） */
    public byte facing = -1;
    /** 图标（第一个物品/输出物的副本），可能为 null */
    public ItemStack icon;
    /** 能源仓：单口最大电压 / 安培 */
    public long maxEu;
    public int amps;
    /** 维护仓：缺几项 */
    public int missing;

    public HatchRef(int x, int y, int z, int kind) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.kind = kind;
    }

    public static int colorOf(int kind) {
        switch (kind) {
            case ITEM_IN:
                return 0x4FC3F7;
            case ITEM_OUT:
                return 0xFFB74D;
            case FLUID_IN:
                return 0x4DD0E1;
            case FLUID_OUT:
                return 0xFF8A65;
            case ENERGY_IN:
                return 0xFFF176;
            case ENERGY_OUT:
                return 0xFF7043;
            case MAINTENANCE:
                return 0xE57373;
            case MUFFLER:
                return 0x9E9E9E;
            case ME:
                return 0xBA68C8;
            case COKE:
                return 0xD4A017;
            default:
                return 0xFFFFFF;
        }
    }

    public static String keyOf(int kind) {
        switch (kind) {
            case ITEM_IN:
                return "item_in";
            case ITEM_OUT:
                return "item_out";
            case FLUID_IN:
                return "fluid_in";
            case FLUID_OUT:
                return "fluid_out";
            case ENERGY_IN:
                return "energy_in";
            case ENERGY_OUT:
                return "energy_out";
            case MAINTENANCE:
                return "maintenance";
            case MUFFLER:
                return "muffler";
            case ME:
                return "me";
            case COKE:
                return "coke_oven";
            default:
                return "other";
        }
    }

    public static String nameOf(int kind) {
        switch (kind) {
            case ITEM_IN:
                return "物品输入";
            case ITEM_OUT:
                return "物品输出";
            case FLUID_IN:
                return "流体输入";
            case FLUID_OUT:
                return "流体输出";
            case ENERGY_IN:
                return "能源仓";
            case ENERGY_OUT:
                return "动力仓";
            case MAINTENANCE:
                return "维护仓";
            case MUFFLER:
                return "消声仓";
            case ME:
                return "ME/双输入";
            case COKE:
                return "焦炉仓";
            default:
                return "其它";
        }
    }

    public static String tierName(int tier) {
        String[] names = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV", "UMV", "UXV",
            "OpV", "MAX" };
        if (tier < 0) return "?";
        return tier < names.length ? names[tier] : "T" + tier;
    }

    public static int tierColor(int tier) {
        int[] colors = { 0x9E9E9E, 0x8D6E63, 0xBDBDBD, 0xFFD54F, 0xFF8A65, 0x64B5F6, 0xBA68C8, 0x4DD0E1, 0x81C784,
            0xF06292, 0xFF7043, 0xFFEB3B, 0x26C6DA, 0x7E57C2, 0xEF5350, 0xFFFFFF };
        if (tier < 0) return 0x9E9E9E;
        return tier < colors.length ? colors[tier] : 0xFFFFFF;
    }

    public static String facingName(int ordinal) {
        switch (ordinal) {
            case 0:
                return "下";
            case 1:
                return "上";
            case 2:
                return "北";
            case 3:
                return "南";
            case 4:
                return "西";
            case 5:
                return "东";
            default:
                return "";
        }
    }
}
