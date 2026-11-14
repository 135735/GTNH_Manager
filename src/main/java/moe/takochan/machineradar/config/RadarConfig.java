package moe.takochan.machineradar.config;

import java.io.File;
import java.lang.reflect.Field;

import net.minecraftforge.common.config.Configuration;

import moe.takochan.machineradar.radar.RadarState;

public final class RadarConfig {

    public static int radius = 96;
    public static int refreshTicks = 20;

    /** 超过这个距离完全不画（格） */
    public static int maxDistance = 128;
    /** 外壳填色最远画到多少格，再远就只剩线框 */
    public static int fillRadius = 40;
    /** 线框最远画到多少格 */
    public static int wireframeRadius = 96;
    /** 名字只在这个距离内画（焦点除外） */
    public static int labelRadius = 48;
    /** 焦点锥角（度）：射线没打中 AABB 时的兜底 */
    public static double coneDegrees = 6.0D;
    /** 副焦点锥角（度）：准星方向上的其它主控也会亮一下，0 = 关闭 */
    public static int secondaryConeDegrees = 3;

    /** 走到控制器多少格内才显示非焦点机器的舱室 */
    public static int hatchNearRadius = 12;
    /** true = 只有焦点机器显示舱室（推荐，画面最干净） */
    public static boolean hatchFocusOnly = true;
    /** 非焦点舱室的透明度（hatchFocusOnly=false 时生效） */
    public static int hatchOthersAlpha = 60;
    /** 在世界里显示舱室朝向的字 */
    public static boolean showHatchFacing = true;

    /** 单方块小点最远显示距离 */
    public static int singleRadius = 24;

    /** 淡化曲线：d / 机器半径 的起点与终点 */
    public static float fadeNear = 4.0F;
    public static float fadeFar = 40.0F;
    /** 淡化下限，低于它不再变淡 */
    public static float fadeFloor = 0.12F;

    /** 外壳填色基础透明度 0-255 */
    public static int overlayAlpha = 48;
    /** 线框基础透明度 0-255 */
    public static int outlineAlpha = 170;

    public static boolean showFills = true;
    public static boolean showWireframe = true;
    /** true = 线框只画准星点选的那一台；false = 全部（非焦点用深度测试 + 更淡，焦点仍是白色强轮廓） */
    public static boolean wireframeFocusOnly = false;
    /** 全量线框时最多给多少台机器算轮廓（按距离取最近）；0 = 不限 */
    public static int wireframeMaxMachines = 96;
    /** true = 焦点线框穿墙（X 光）；false = 写深度后正常遮挡（凹形结构不会露出背面杂线） */
    public static boolean wireframeXray = false;
    /** 分组区域预览：0=关 1=分组 AABB 2=区块对齐预览 */
    public static int groupPreviewMode = 0;
    /** 独立分组图层（只看分区，不画机器） */
    public static boolean showGroupLayer = false;
    /** 工厂页统计范围：true=只统计已分组的机器，false=全局 */
    public static boolean statsGroupedOnly = false;
    /** true=统计分帧处理（每帧 96 台），false=一次算完 */
    public static boolean statsIncremental = true;
    public static boolean showIcons = true;
    /** 机器图标最远显示距离 */
    public static int iconRadius = 48;

    public static boolean showHatches = true;
    public static boolean showLabels = true;
    public static int maxLabels = 40;
    /** true = 外壳填色也无视深度（X 光）；默认 false，只显示最前一层 */
    public static boolean overlayXray = false;

    private static Configuration cfg;

    private RadarConfig() {}

    public static void load(File file) {
        cfg = new Configuration(file);
        try {
            cfg.load();
            radius = cfg.getInt("radius", "general", 96, 16, 512, "雷达半径（格）");
            refreshTicks = cfg.getInt("refreshTicks", "general", 20, 5, 200, "服务端重扫间隔（tick）");

            maxDistance = cfg.getInt("maxDistance", "render", 128, 16, 512, "超过该距离不显示（格）");
            fillRadius = cfg.getInt("fillRadius", "render", 40, 8, 256, "外壳填色最远距离，再远只剩线框");
            wireframeRadius = cfg.getInt("wireframeRadius", "render", 96, 8, 512, "线框最远距离");
            labelRadius = cfg.getInt("labelRadius", "render", 48, 8, 256, "名字最大绘制距离（焦点除外）");
            coneDegrees = cfg.getFloat("coneDegrees", "render", 6.0F, 1.0F, 30.0F, "焦点锥角（度）");
            secondaryConeDegrees = cfg.getInt("secondaryConeDegrees", "render", 3, 0, 15, "副焦点锥角（度），0=关闭");

            hatchNearRadius = cfg.getInt("hatchNearRadius", "render", 12, 2, 64, "非焦点舱室的显示距离");
            hatchFocusOnly = cfg.getBoolean("hatchFocusOnly", "render", true, "只有焦点机器显示舱室");
            hatchOthersAlpha = cfg.getInt("hatchOthersAlpha", "render", 60, 0, 255, "非焦点舱室透明度");
            showHatchFacing = cfg.getBoolean("showHatchFacing", "render", true, "世界里显示舱室朝向");

            singleRadius = cfg.getInt("singleRadius", "render", 24, 4, 96, "单方块小点最远距离");

            fadeNear = cfg.getFloat("fadeNear", "render", 4.0F, 0.5F, 64.0F, "淡化起点（距离/半径）");
            fadeFar = cfg.getFloat("fadeFar", "render", 40.0F, 1.0F, 256.0F, "淡化终点（距离/半径）");
            fadeFloor = cfg.getFloat("fadeFloor", "render", 0.12F, 0.0F, 0.9F, "淡化下限");

            overlayAlpha = cfg.getInt("overlayAlpha", "render", 48, 5, 255, "外壳填色透明度");
            outlineAlpha = cfg.getInt("outlineAlpha", "render", 170, 10, 255, "线框透明度");

            showFills = cfg.getBoolean("showFills", "render", true, "显示外壳填色");
            showWireframe = cfg.getBoolean("showWireframe", "render", true, "显示线框轮廓");
            wireframeFocusOnly = cfg
                .getBoolean("wireframeFocusOnly", "render", false, "线框只画准星点选的机器（false=全部）");
            wireframeMaxMachines = cfg
                .getInt("wireframeMaxMachines", "render", 96, 0, 512, "全量线框最多算多少台（0=不限）");
            wireframeXray = cfg
                .getBoolean("wireframeXray", "render", false, "焦点线框穿墙（true 会看到背面杂线）");
            groupPreviewMode = cfg
                .getInt("groupPreviewMode", "render", 0, 0, 2, "分组区域预览：0=关 1=分组框 2=区块对齐");
            showGroupLayer = cfg.getBoolean("showGroupLayer", "render", false, "独立分组图层（只看分区，不画机器）");
            statsGroupedOnly = cfg.getBoolean("statsGroupedOnly", "ui", false, "工厂页只统计已分组的机器");
            statsIncremental = cfg.getBoolean("statsIncremental", "ui", true, "工厂页统计分帧处理");
            showIcons = cfg.getBoolean("showIcons", "render", true, "显示机器图标");
            iconRadius = cfg.getInt("iconRadius", "render", 48, 8, 128, "机器图标最远距离");

            showHatches = cfg.getBoolean("showHatches", "render", true, "显示舱室");
            showLabels = cfg.getBoolean("showLabels", "render", true, "显示机器名字");
            maxLabels = cfg.getInt("maxLabels", "render", 40, 4, 200, "同屏最多名字数");
            overlayXray = cfg.getBoolean("overlayXray", "render", false, "外壳填色 X 光模式（true=穿墙）");

            int layer = cfg.getInt("layer", "render", 0, 0, 2, "图层：0=全部 1=仅多方块 2=仅单方块");
            RadarState.layer = layer;
            RadarState.groupLayer = showGroupLayer;
        } catch (Exception e) {
            System.err.println("[MachineRadar] config load failed: " + e);
        } finally {
            if (cfg.hasChanged()) cfg.save();
        }
    }

    /** 设置界面点"保存"时写回磁盘 */
    public static void save() {
        if (cfg == null) return;
        try {
            putInt("general", "radius", radius);
            putInt("general", "refreshTicks", refreshTicks);

            putInt("render", "maxDistance", maxDistance);
            putInt("render", "fillRadius", fillRadius);
            putInt("render", "wireframeRadius", wireframeRadius);
            putInt("render", "labelRadius", labelRadius);
            putFloat("render", "coneDegrees", (float) coneDegrees);
            putInt("render", "secondaryConeDegrees", secondaryConeDegrees);

            putInt("render", "hatchNearRadius", hatchNearRadius);
            putBool("render", "hatchFocusOnly", hatchFocusOnly);
            putInt("render", "hatchOthersAlpha", hatchOthersAlpha);
            putBool("render", "showHatchFacing", showHatchFacing);

            putInt("render", "singleRadius", singleRadius);

            putFloat("render", "fadeNear", fadeNear);
            putFloat("render", "fadeFar", fadeFar);
            putFloat("render", "fadeFloor", fadeFloor);

            putInt("render", "overlayAlpha", overlayAlpha);
            putInt("render", "outlineAlpha", outlineAlpha);

            putBool("render", "showFills", showFills);
            putBool("render", "showWireframe", showWireframe);
            putBool("render", "wireframeFocusOnly", wireframeFocusOnly);
            putInt("render", "wireframeMaxMachines", wireframeMaxMachines);
            putBool("render", "wireframeXray", wireframeXray);
            cfg.get("render", "groupPreviewMode", 0).set(groupPreviewMode);
            putBool("render", "showGroupLayer", showGroupLayer);
            putBool("ui", "statsGroupedOnly", statsGroupedOnly);
            putBool("ui", "statsIncremental", statsIncremental);
            putBool("render", "showIcons", showIcons);
            putInt("render", "iconRadius", iconRadius);

            putBool("render", "showHatches", showHatches);
            putBool("render", "showLabels", showLabels);
            putInt("render", "maxLabels", maxLabels);
            putBool("render", "overlayXray", overlayXray);

            cfg.get("render", "layer", 0).set(RadarState.layer);
            cfg.save();
        } catch (Throwable t) {
            System.err.println("[MachineRadar] config save failed: " + t);
        }
    }

    public static void saveLayer(int layer) {
        RadarState.layer = layer;
        if (cfg == null) return;
        cfg.get("render", "layer", 0).set(layer);
        cfg.save();
    }

    public static void saveGroupLayer(boolean on) {
        showGroupLayer = on;
        RadarState.groupLayer = on;
        if (cfg == null) return;
        cfg.get("render", "showGroupLayer", false)
            .set(on);
        cfg.save();
    }

    private static void putInt(String cat, String key, int v) {
        cfg.get(cat, key, v).set(v);
    }

    private static void putFloat(String cat, String key, float v) {
        cfg.get(cat, key, (double) v).set((double) v);
    }

    private static void putBool(String cat, String key, boolean v) {
        cfg.get(cat, key, v).set(v);
    }

    // ------------------------------------------------------------------ 设置界面用的反射读写

    public static boolean reflectGetBool(String name) {
        try {
            Field f = RadarConfig.class.getField(name);
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void reflectSetBool(String name, boolean v) {
        try {
            RadarConfig.class.getField(name)
                .setBoolean(null, v);
        } catch (Throwable ignored) {}
    }

    public static int reflectGetInt(String name) {
        try {
            return RadarConfig.class.getField(name)
                .getInt(null);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void reflectSetInt(String name, int v) {
        try {
            RadarConfig.class.getField(name)
                .setInt(null, v);
        } catch (Throwable ignored) {}
    }
}