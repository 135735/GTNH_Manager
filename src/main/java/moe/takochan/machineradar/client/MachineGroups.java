package moe.takochan.machineradar.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;

/**
 * 客户端分组仓库：新建/重命名/删除分组、把机器加进组、按机器反查组。
 *
 * <p>数据在 {@code config/machineradar/groups.json}；只在客户端线程读写，
 * 不参与服务端扫描，也不影响世界渲染数据。
 */
public final class MachineGroups {

    private static final Logger LOG = LogManager.getLogger("MachineRadar");

    private static final int[] PALETTE = { 0x4FC3F7, 0xFFB74D, 0x81C784, 0xBA68C8, 0xF06292, 0xFFF176, 0x4DD0E1,
        0x8D6E63, 0xE57373, 0x9575CD, 0xA1887F, 0x90A4AE };

    public static final List<MachineGroup> GROUPS = new ArrayList<MachineGroup>();

    private static final Map<String, MachineGroup> INDEX = new HashMap<String, MachineGroup>();
    private static boolean loaded;
    private static String loadedWorld = "";
    /** 上次加载分组时对应的 World 对象；用引用比较做快速路径，避免每帧取世界名 */
    private static Object loadedWorldRef;
    private static int nextColor;

    private MachineGroups() {}

    public static void ensureLoaded() {
        Minecraft mc = Minecraft.getMinecraft();
        Object worldRef = mc == null ? null : mc.theWorld;
        // 快速路径：同一世界对象直接返回（每帧会被 GUI 调用很多次）
        if (loaded && worldRef == loadedWorldRef) return;

        GROUPS.clear();
        INDEX.clear();
        nextColor = 0;
        loaded = true;
        loadedWorldRef = worldRef;
        loadedWorld = currentWorld(mc);

        File f = file();
        if (f == null || !f.isFile()) {
            reindex();
            return;
        }
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Data data = new Gson().fromJson(r, Data.class);
            if (data != null && data.groups != null) {
                for (MachineGroup group : data.groups) {
                    if (group == null || group.name == null) continue;
                    if (group.machines == null) group.machines = new java.util.LinkedHashSet<String>();
                    GROUPS.add(group);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[MachineRadar] groups load failed: {}", t.toString());
        }
        reindex();
    }

    private static String currentWorld(Minecraft mc) {
        if (mc == null || mc.theWorld == null) return "unknown";
        String name = mc.theWorld.getWorldInfo()
            .getWorldName();
        if (name == null) return "unknown";
        return sanitize(name);
    }

    /** 只保留安全字符；不用 replaceAll（正则每次调用都要编译，实测 ~296ns/call） */
    private static String sanitize(String name) {
        int len = name.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'
                || c == '-';
            sb.append(safe ? c : '_');
        }
        return sb.toString();
    }

    public static MachineGroup create(MachineRecord first) {
        ensureLoaded();
        MachineGroup group = new MachineGroup();
        group.name = "分区 " + (GROUPS.size() + 1);
        group.color = PALETTE[Math.abs(nextColor++) % PALETTE.length];
        if (first != null) group.add(first);
        GROUPS.add(group);
        reindex();
        save();
        return group;
    }

    /**
     * 用一批机器新建分组（列表「新建分组」/ 选区归组都走这里）。
     *
     * @param recs   成员；可以为空
     * @param dim    区域维度
     * @param region {minX,minY,minZ,maxX,maxY,maxZ}；null = 不记录区域
     */
    public static MachineGroup createWith(List<MachineRecord> recs, int dim, int[] region) {
        ensureLoaded();
        MachineGroup group = new MachineGroup();
        group.name = "分区 " + (GROUPS.size() + 1);
        group.color = PALETTE[Math.abs(nextColor++) % PALETTE.length];
        if (recs != null) {
            for (int i = 0; i < recs.size(); i++) {
                MachineRecord rec = recs.get(i);
                if (rec == null) continue;
                MachineGroup old = groupOf(rec);
                if (old != null) old.remove(rec);
                group.add(rec);
            }
        }
        if (region != null) group.setRegion(dim, region);
        GROUPS.add(group);
        reindex();
        save();
        return group;
    }

    public static void setRegion(MachineGroup group, int dim, int[] box) {
        if (group == null || box == null || box.length != 6) return;
        ensureLoaded();
        group.setRegion(dim, box);
        save();
    }

    public static void clearRegion(MachineGroup group) {
        if (group == null || !group.hasRegion()) return;
        group.clearRegion();
        save();
    }

    public static void rename(MachineGroup group, String name) {
        if (group == null || name == null) return;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return;
        group.name = trimmed;
        save();
    }

    public static void delete(MachineGroup group) {
        if (group == null) return;
        GROUPS.remove(group);
        reindex();
        save();
    }

    /** 把机器归到某个组（一台机器同一时间只属于一个组） */
    public static void assign(MachineRecord rec, MachineGroup group) {
        if (rec == null || group == null) return;
        List<MachineRecord> one = new ArrayList<MachineRecord>(1);
        one.add(rec);
        assignAll(one, group);
    }

    /**
     * 批量归组：只在最后写一次盘。
     *
     * <p>以前列表里「加入组」是一台调用一次 {@link #assign}，一百台就要重写一百次
     * {@code groups-*.json}；框选批量归组之后成员数会更大，所以必须有这条批量路径。
     */
    public static void assignAll(List<MachineRecord> recs, MachineGroup group) {
        if (group == null || recs == null || recs.isEmpty()) return;
        ensureLoaded();
        int moved = 0;
        for (int i = 0; i < recs.size(); i++) {
            MachineRecord rec = recs.get(i);
            if (rec == null) continue;
            MachineGroup old = groupOf(rec);
            if (old == group) continue;
            if (old != null) old.remove(rec);
            if (group.add(rec)) moved++;
        }
        if (moved == 0) return;
        reindex();
        save();
    }

    /**
     * 用一批机器**替换**分组的全部成员（框选「替换现有分组」走这里）：
     * 先清空旧成员，再加入框内的机器；区域是否更新由调用方另行决定。
     */
    public static void replaceAll(List<MachineRecord> recs, MachineGroup group) {
        if (group == null) return;
        ensureLoaded();
        group.machines.clear();
        if (recs != null) {
            for (int i = 0; i < recs.size(); i++) {
                MachineRecord rec = recs.get(i);
                if (rec == null) continue;
                MachineGroup old = groupOf(rec);
                if (old != null && old != group) old.remove(rec);
                group.add(rec);
            }
        }
        reindex();
        save();
    }

    public static void unassign(MachineRecord rec) {        if (rec == null) return;
        ensureLoaded();
        MachineGroup old = groupOf(rec);
        if (old == null) return;
        old.remove(rec);
        reindex();
        save();
    }

    public static MachineGroup groupOf(MachineRecord rec) {
        if (rec == null) return null;
        ensureLoaded();
        return INDEX.get(MachineGroup.keyOf(rec));
    }

    public static MachineGroup byName(String name) {
        ensureLoaded();
        for (MachineGroup group : GROUPS) {
            if (group.name.equalsIgnoreCase(name)) return group;
        }
        return null;
    }

    /** 当前快照里属于该组的机器（在线），按组过滤用 */
    public static List<MachineRecord> onlineMembers(MachineGroup group) {
        List<MachineRecord> out = new ArrayList<MachineRecord>();
        if (group == null) return out;
        List<MachineRecord> all = moe.takochan.machineradar.radar.RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (group.contains(rec)) out.add(rec);
        }
        return out;
    }

    public static int onlineCount(MachineGroup group, int dim) {
        if (group == null) return 0;
        int n = 0;
        List<MachineRecord> all = moe.takochan.machineradar.radar.RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim == dim && group.contains(rec)) n++;
        }
        return n;
    }

    /**
     * 分组在当前维度的范围。
     *
     * <p>有显式区域（框选定出来的）就直接用区域——这样分组范围跟"成员机器包围盒"
     * 解耦，拆掉机器、或者成员只落在区域一角时，预览框都不会变形；
     * 没有区域才退回成员结构 bbox 的并集（旧行为）。
     *
     * @return {minX,minY,minZ,maxX,maxY,maxZ}；两者都没有返回 null
     */
    public static int[] bounds(MachineGroup group, int dim) {
        if (group == null) return null;
        if (group.hasRegion(dim)) return group.regionCopy();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        List<MachineRecord> all = moe.takochan.machineradar.radar.RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim != dim || !group.contains(rec)) continue;
            any = true;
            if (rec.minX < minX) minX = rec.minX;
            if (rec.minY < minY) minY = rec.minY;
            if (rec.minZ < minZ) minZ = rec.minZ;
            if (rec.maxX > maxX) maxX = rec.maxX;
            if (rec.maxY > maxY) maxY = rec.maxY;
            if (rec.maxZ > maxZ) maxZ = rec.maxZ;
        }
        return any ? new int[] { minX, minY, minZ, maxX, maxY, maxZ } : null;
    }

    private static void reindex() {
        INDEX.clear();
        for (int i = 0; i < GROUPS.size(); i++) {
            MachineGroup group = GROUPS.get(i);
            for (String key : group.machines) INDEX.put(key, group);
        }
    }

    private static File file() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return null;
        return new File(new File(mc.mcDataDir, "config/machineradar"), "groups-" + loadedWorld + ".json");
    }

    public static void save() {
        ensureLoaded();
        File f = file();
        if (f == null) return;
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            Data data = new Data();
            data.groups = GROUPS;
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(data, w);
        } catch (Throwable t) {
            LOG.warn("[MachineRadar] groups save failed: {}", t.toString());
        }
    }

    public static final class Data {

        public List<MachineGroup> groups = new ArrayList<MachineGroup>();
    }
}
