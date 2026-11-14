package moe.takochan.machineradar.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import moe.takochan.machineradar.client.EcgPlot;
import moe.takochan.machineradar.client.FactoryStats;
import moe.takochan.machineradar.client.MachineGroups;
import moe.takochan.machineradar.client.MachineHistory;
import moe.takochan.machineradar.client.ProductionLedger;
import moe.takochan.machineradar.client.RadarText;
import moe.takochan.machineradar.config.RadarConfig;
import moe.takochan.machineradar.radar.HatchRef;
import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarActions;
import moe.takochan.machineradar.radar.RadarState;
import moe.takochan.machineradar.radar.RegionSelection;
import moe.takochan.machineradar.radar.RemoteAction;

/**
 * 工厂列表：左栏分组 / 中栏机器列表 / 右栏详情。
 *
 * <p>多选：单击单选，Ctrl 单击切换，Shift 单击区间；Ctrl+A 全选。
 * 选中后「加入组…」直接选目标（新建 / 已有），不用先到左栏点一个组；
 * 「＋ 新建分组」会把当前选中全部带进新组。也保留把行拖到左栏分组的快捷方式。
 *
 * <p>底部两排：动作 / 视图开关（统计范围、分帧、调试、区域预览、3D 选区）。
 */
public class GuiMachineList extends GuiScreen {

    private static final int ROW_H = 18;
    private static final int PAD = 12;
    private static final int HEADER_H = 46;
    private static final int FOOTER_H = 62;
    private static final int SIDEBAR_W = 118;
    private static final int GAP = 10;
    private static final int GROUP_ROW_H = 16;

    private static final int SORT_DISTANCE = 0;
    private static final int SORT_NAME = 1;
    private static final int SORT_TIER = 2;
    private static final int SORT_STATUS = 3;
    private static final String[] SORT_NAMES = { "距离", "名称", "电压", "状态" };

    private static final int TRACK_MS = 15_000;

    private static String lastQuery = "";
    private static int lastGroupMode = -1;
    private static boolean debug;
    /** 分析面板：0=关 1=全局预览 2=分组预览 */
    private static int analysisMode;
    /** 统计窗口：0=秒 1=分钟 2=小时 */
    private static int statWindow;

    private final List<MachineRecord> rows = new ArrayList<MachineRecord>();
    private final Set<String> selection = new HashSet<String>();

    private GuiTextField search;
    private List<MachineRecord> lastSnapshot = Collections.emptyList();
    private String lastQuerySeen = "";

    private int filter;
    private int sortMode = SORT_DISTANCE;
    private int scroll;
    /** -1 = 全部，-2 = 未分组，>=0 = MachineGroups.GROUPS 下标 */
    private int groupMode = -1;

    /** 左栏自定义分组的滚动偏移（像素） */
    private int groupScroll;
    private boolean draggingListBar;
    private boolean draggingGroupBar;
    private int lastMouseX, lastMouseY;
    /** 布局需要重算（切换分析面板时不能在 actionPerformed 里 initGui，会 CME） */
    private boolean layoutDirty;

    private int analysisX, analysisY, analysisW, analysisH;

    /** 主选中（详情/心电图/追踪的对象） */
    private int selDim = Integer.MIN_VALUE;
    private int selX, selY, selZ;
    private int anchorIndex = -1;

    private int clickRow = -1;
    private long clickAt;

    private int dragRow = -1;
    private int dragX, dragY;
    private boolean dragging;

    private int panelX, panelY, panelW, panelH;
    private int sidebarX, sidebarY, sidebarW, sidebarH, groupListTop;
    private int listX, listY, listW, listH;
    private int detailX, detailY, detailW;

    private int countAll, countUngrouped;
    private int[] groupOnline = new int[0];

    private double px, py, pz;

    private final Comparator<MachineRecord> comparator = new Comparator<MachineRecord>() {

        @Override
        public int compare(MachineRecord a, MachineRecord b) {
            switch (sortMode) {
                case SORT_NAME: {
                    int c = a.name.compareToIgnoreCase(b.name);
                    if (c != 0) return c;
                    break;
                }
                case SORT_TIER: {
                    int c = Integer.compare(b.tier, a.tier);
                    if (c != 0) return c;
                    break;
                }
                case SORT_STATUS: {
                    int c = Integer.compare(statusRank(a), statusRank(b));
                    if (c != 0) return c;
                    break;
                }
                default:
                    break;
            }
            return Double.compare(distSq(a), distSq(b));
        }
    };

    @Override
    public void initGui() {
        buttonList.clear();
        MachineGroups.ensureLoaded();
        filter = RadarState.layer;
        scroll = 0;
        groupMode = lastGroupMode;
        if (groupMode >= MachineGroups.GROUPS.size()) groupMode = -1;

        int w = width;
        int h = height;
        boolean analysis = analysisMode > 0;
        panelW = Math.min(w - 40, analysis ? 1180 : 820);
        if (panelW < 520) panelW = Math.max(360, w - 20);
        panelH = Math.min(h - 40, 460);
        if (panelH < 300) panelH = Math.max(250, h - 20);
        panelX = (w - panelW) / 2;
        panelY = (h - panelH) / 2;

        int contentY = panelY + HEADER_H;
        int contentH = panelH - HEADER_H - FOOTER_H;
        sidebarX = panelX + PAD;
        sidebarY = contentY;
        sidebarW = SIDEBAR_W;
        sidebarH = contentH;
        groupListTop = sidebarY + 14;

        int inner = panelW - PAD * 2 - sidebarW - GAP * 2;
        listX = sidebarX + sidebarW + GAP;
        analysisY = contentY;
        analysisH = contentH;
        if (analysis) {
            analysisW = Math.max(260, Math.min(340, inner * 32 / 100));
            analysisX = panelX + panelW - PAD - analysisW;
            int mid = Math.max(220, inner - analysisW - GAP);
            listW = Math.max(170, mid * 46 / 100);
            detailX = listX + listW + GAP;
            detailY = contentY;
            detailW = Math.max(140, analysisX - GAP - detailX);
        } else {
            analysisW = 0;
            analysisX = 0;
            listW = Math.max(170, inner * 45 / 100);
            detailX = listX + listW + GAP;
            detailY = contentY;
            detailW = Math.max(120, panelX + panelW - PAD - detailX);
        }
        listY = contentY + 22;
        listH = contentH - 22;

        search = new GuiTextField(fontRendererObj, listX, contentY, listW, 16);
        search.setMaxStringLength(48);
        search.setText(lastQuery == null ? "" : lastQuery);
        search.setFocused(true);

        int row1 = panelY + panelH - FOOTER_H + 6;
        int row2 = row1 + 24;
        int bx = panelX + PAD;
        buttonList.add(new GuiButton(900, bx, row1, 54, 18, "追踪"));
        bx += 60;
        buttonList.add(new GuiButton(909, bx, row1, 54, 18, "开关"));
        bx += 60;
        buttonList.add(new GuiButton(904, bx, row1, 66, 18, "加入组…"));
        bx += 72;
        buttonList.add(new GuiButton(905, bx, row1, 54, 18, "移出组"));
        bx += 60;
        buttonList.add(new GuiButton(908, bx, row1, 54, 18, "心电图"));
        bx += 60;
        buttonList.add(new GuiButton(907, bx, row1, 44, 18, "地图"));
        bx += 50;
        buttonList.add(new GuiButton(923, bx, row1, 54, 18, "选区"));
        buttonList.add(new GuiButton(901, panelX + panelW - PAD - 50, row1, 50, 18, "关闭"));

        bx = panelX + PAD;
        buttonList.add(new GuiButton(902, bx, row2, 76, 18, filterLabel()));
        bx += 84;
        buttonList.add(new GuiButton(903, bx, row2, 76, 18, sortLabel()));
        bx += 84;
        buttonList.add(new GuiButton(920, bx, row2, 84, 18, statsLabel()));
        bx += 92;
        buttonList.add(new GuiButton(921, bx, row2, 66, 18, incrementalLabel()));
        bx += 74;
        buttonList.add(new GuiButton(922, bx, row2, 66, 18, debugLabel()));
        bx += 74;
        buttonList.add(new GuiButton(910, bx, row2, 88, 18, areaLabel()));
        bx += 94;
        buttonList.add(new GuiButton(929, bx, row2, 88, 18, analysisLabel()));

        if (analysisMode > 0) {
            int bw = Math.max(110, (analysisW - 12) / 2);
            buttonList.add(new GuiButton(930, analysisX + 4, analysisY + 14, bw, 16, windowLabel()));
            buttonList.add(new GuiButton(931, analysisX + 8 + bw, analysisY + 14, bw, 16, scopeLabel()));
        }

        buttonList.add(new GuiButton(906, sidebarX, sidebarY + sidebarH - 20, sidebarW, 18, "＋ 新建分组"));

        RadarState.enabled = true;

        rebuild();
    }

    @Override
    public void onGuiClosed() {
        lastQuery = search == null ? "" : search.getText();
        lastGroupMode = groupMode;
    }

    @Override
    public void updateScreen() {
        if (search != null) search.updateCursorCounter();
        if (search != null && !search.getText()
            .equals(lastQuerySeen)) {
            lastQuerySeen = search.getText();
            rebuild();
        }
        if (RadarState.snapshot != lastSnapshot) {
            rebuild();
        }
    }

    // ------------------------------------------------------------------ 数据

    private MachineGroup selectedGroup() {
        if (groupMode < 0 || groupMode >= MachineGroups.GROUPS.size()) return null;
        return MachineGroups.GROUPS.get(groupMode);
    }

    private void rebuild() {
        List<MachineRecord> all = RadarState.snapshot;
        lastSnapshot = all;
        if (mc == null || mc.thePlayer == null) return;
        px = mc.thePlayer.posX;
        py = mc.thePlayer.posY;
        pz = mc.thePlayer.posZ;
        rows.clear();

        String q = search == null ? "" : search.getText()
            .trim()
            .toLowerCase(Locale.ROOT);
        MachineGroup gm = selectedGroup();

        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (filter == RadarState.LAYER_MULTI && !rec.multi) continue;
            if (filter == RadarState.LAYER_SINGLE && rec.multi) continue;
            if (rec.dim != mc.thePlayer.dimension) continue;
            if (groupMode == -2) {
                if (MachineGroups.groupOf(rec) != null) continue;
            } else if (gm != null) {
                if (!gm.contains(rec)) continue;
            }
            if (q.length() > 0 && !matches(rec, q)) continue;
            rows.add(rec);
        }
        Collections.sort(rows, comparator);
        clampScroll();
        clampGroupScroll();
        refreshCounts();
    }

    private void refreshCounts() {
        List<MachineRecord> all = RadarState.snapshot;
        countAll = 0;
        countUngrouped = 0;
        if (groupOnline.length != MachineGroups.GROUPS.size()) {
            groupOnline = new int[MachineGroups.GROUPS.size()];
        } else {
            for (int i = 0; i < groupOnline.length; i++) groupOnline[i] = 0;
        }
        if (mc == null || mc.thePlayer == null) return;
        int dim = mc.thePlayer.dimension;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim != dim) continue;
            countAll++;
            MachineGroup group = MachineGroups.groupOf(rec);
            if (group == null) {
                countUngrouped++;
            } else {
                int idx = MachineGroups.GROUPS.indexOf(group);
                if (idx >= 0 && idx < groupOnline.length) groupOnline[idx]++;
            }
        }
    }

    private static boolean matches(MachineRecord rec, String q) {
        if (rec.name != null && rec.name.toLowerCase(Locale.ROOT)
            .contains(q)) return true;
        if (rec.metaName != null && rec.metaName.toLowerCase(Locale.ROOT)
            .contains(q)) return true;
        if (HatchRef.tierName(rec.tier)
            .toLowerCase(Locale.ROOT)
            .contains(q)) return true;
        if (Integer.toString(rec.id)
            .contains(q)) return true;
        if (rec.multi && "多方块".contains(q)) return true;
        if (!rec.multi && "单方块".contains(q)) return true;
        return false;
    }

    private void clampScroll() {
        int max = Math.max(0, rows.size() * ROW_H - listH);
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    // ------------------------------------------------------------------ 选择

    private boolean isSelected(MachineRecord rec) {
        return selection.contains(MachineGroup.keyOf(rec));
    }

    private boolean isPrimary(MachineRecord rec) {
        return selDim == rec.dim && selX == rec.x && selY == rec.y && selZ == rec.z;
    }

    private MachineRecord primaryRec() {
        if (selDim == Integer.MIN_VALUE) return null;
        List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim == selDim && rec.x == selX && rec.y == selY && rec.z == selZ) return rec;
        }
        return null;
    }

    private List<MachineRecord> selectedMachines() {
        List<MachineRecord> out = new ArrayList<MachineRecord>();
        if (selection.isEmpty()) return out;
        List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (selection.contains(MachineGroup.keyOf(rec))) out.add(rec);
        }
        return out;
    }

    private void setPrimary(MachineRecord rec) {
        if (rec == null) {
            selDim = Integer.MIN_VALUE;
            return;
        }
        selDim = rec.dim;
        selX = rec.x;
        selY = rec.y;
        selZ = rec.z;
    }

    private void selectOnly(MachineRecord rec, int index) {
        selection.clear();
        if (rec != null) {
            selection.add(MachineGroup.keyOf(rec));
            setPrimary(rec);
        } else {
            setPrimary(null);
        }
        anchorIndex = index;
    }

    private void toggleSelect(MachineRecord rec, int index) {
        String key = MachineGroup.keyOf(rec);
        if (selection.remove(key)) {
            if (isPrimary(rec)) setPrimary(null);
        } else {
            selection.add(key);
            setPrimary(rec);
        }
        anchorIndex = index;
    }

    private void selectRange(int from, int to) {
        if (from < 0 || rows.isEmpty()) return;
        int lo = Math.min(from, to);
        int hi = Math.max(from, to);
        lo = Math.max(0, lo);
        hi = Math.min(rows.size() - 1, hi);
        selection.clear();
        for (int i = lo; i <= hi; i++) selection.add(MachineGroup.keyOf(rows.get(i)));
        setPrimary(rows.get(to < 0 ? 0 : Math.min(to, rows.size() - 1)));
    }

    private void selectAllRows() {
        selection.clear();
        for (int i = 0; i < rows.size(); i++) selection.add(MachineGroup.keyOf(rows.get(i)));
        if (!rows.isEmpty()) setPrimary(rows.get(0));
        anchorIndex = 0;
    }

    // ------------------------------------------------------------------ 分组 / 动作

    private void assignTo(MachineGroup group) {
        if (group == null) return;
        List<MachineRecord> sel = selectedMachines();
        if (sel.isEmpty()) return;
        MachineGroups.assignAll(sel, group);
        say("已把 " + sel.size() + " 台加入 " + group.name);
        rebuild();
    }

    private void assignSelected() {
        List<MachineRecord> sel = selectedMachines();
        if (sel.isEmpty()) {
            say("先选中机器（Ctrl/Shift 多选，Ctrl+A 全选）");
            return;
        }
        if (mc == null || mc.thePlayer == null) return;
        int dim = mc.thePlayer.dimension;
        int[] region = RegionSelection.isComplete() && RegionSelection.dim() == dim ? RegionSelection.box() : null;
        mc.displayGuiScreen(new GuiGroupAssign(sel, false, true, dim, region));
    }

    /** 从列表里直接启动 3D 选区（关掉界面回到世界里选两个角点） */
    private void startRegionTool() {
        if (mc == null || mc.thePlayer == null) return;
        int dim = mc.thePlayer.dimension;
        RegionSelection.clear();
        RegionSelection.begin(dim);
        mc.displayGuiScreen(null);
        say("3D 选区：准星对着方块按 V 设角点1，再按 V 设角点2（Ctrl+V 取消）");
    }

    private void unassignSelection() {
        List<MachineRecord> sel = selectedMachines();
        if (sel.isEmpty()) return;
        for (int i = 0; i < sel.size(); i++) MachineGroups.unassign(sel.get(i));
        say("已移出 " + sel.size() + " 台");
        rebuild();
    }

    private void createGroup() {
        List<MachineRecord> sel = selectedMachines();
        if (sel.isEmpty()) {
            MachineRecord rec = primaryRec();
            if (rec != null) sel.add(rec);
        }
        int dim = mc == null || mc.thePlayer == null ? 0 : mc.thePlayer.dimension;
        MachineGroup group = MachineGroups.createWith(sel, dim, null);
        groupMode = MachineGroups.GROUPS.indexOf(group);
        lastGroupMode = groupMode;
        scroll = 0;
        rebuild();
        say("已新建 " + group.name + "（" + sel.size() + " 台）");
    }

    private void toggleWorking() {
        List<MachineRecord> sel = selectedMachines();
        if (sel.isEmpty()) return;
        if (MinecraftServer.getServer() == null) {
            say("远程开关机只在单机（integrated server）可用");
            return;
        }
        int n = 0;
        for (int i = 0; i < sel.size(); i++) {
            MachineRecord rec = sel.get(i);
            boolean target = !rec.allowed;
            RadarActions.submit(new RemoteAction(rec, target));
            rec.allowed = target; // 乐观更新，下一次扫描会被服务端状态覆盖
            n++;
        }
        say("已发送开关机 " + n + " 台（下一帧生效）");
    }

    private void trackSelected() {
        MachineRecord rec = primaryRec();
        if (rec == null) return;

        MachineRecord tracked = RadarState.tracked();
        boolean same = tracked != null && tracked.dim == rec.dim
            && tracked.x == rec.x
            && tracked.y == rec.y
            && tracked.z == rec.z;
        if (same) {
            RadarState.clearTracked();
            say(EnumChatFormatting.GRAY + "已取消追踪");
        } else {
            RadarState.track(rec, TRACK_MS);
            say(
                EnumChatFormatting.AQUA + "追踪 "
                    + EnumChatFormatting.WHITE
                    + rec.name
                    + EnumChatFormatting.GRAY
                    + "  "
                    + rec.x
                    + ", "
                    + rec.y
                    + ", "
                    + rec.z
                    + "  "
                    + (TRACK_MS / 1000)
                    + " 秒");
        }
        mc.displayGuiScreen(null);
    }

    private void say(String msg) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[MachineRadar] " + msg));
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);

        if (search != null && search.isFocused() && search.textboxKeyTyped(typedChar, keyCode)) {
            lastQuerySeen = search.getText();
            rebuild();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_A && ctrl) {
            selectAllRows();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            trackSelected();
            return;
        }
        if (keyCode == Keyboard.KEY_UP) {
            moveSelection(-1, shift);
            return;
        }
        if (keyCode == Keyboard.KEY_DOWN) {
            moveSelection(1, shift);
            return;
        }
        if (keyCode == Keyboard.KEY_F && ctrl) {
            if (search != null) {
                search.setFocused(true);
                search.setText(lastQuery == null ? "" : lastQuery);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void moveSelection(int delta, boolean extend) {
        if (rows.isEmpty()) return;
        int index = -1;
        MachineRecord primary = primaryRec();
        for (int i = 0; i < rows.size(); i++) {
            if (isPrimary(rows.get(i))) {
                index = i;
                break;
            }
        }
        if (index < 0) index = delta > 0 ? -1 : rows.size();
        int next = index + delta;
        if (next < 0) next = 0;
        if (next >= rows.size()) next = rows.size() - 1;

        if (extend && anchorIndex >= 0) selectRange(anchorIndex, next);
        else {
            selectOnly(rows.get(next), next);
            anchorIndex = next;
        }
        ensureVisible(next);
    }

    private void ensureVisible(int index) {
        int top = index * ROW_H;
        if (top < scroll) scroll = top;
        int bottom = top + ROW_H;
        if (bottom > scroll + listH) scroll = bottom - listH;
        clampScroll();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 1) {
            int entry = sidebarEntryAt(mouseX, mouseY);
            if (entry >= 0 && entry < MachineGroups.GROUPS.size()) {
                mc.displayGuiScreen(new GuiGroupName(MachineGroups.GROUPS.get(entry), primaryRec()));
                return;
            }
        }
        if (mouseButton != 0) return;

        // 滑块拖动（列表 / 左栏）
        if (listBarVisible() && mouseX >= listBarX() - 4 && mouseX <= listBarX() + 5
            && mouseY >= listY && mouseY <= listY + listH) {
            draggingListBar = true;
            scrollListTo(mouseY);
            return;
        }
        if (groupBarVisible() && mouseX >= sidebarX + sidebarW - 6 && mouseX <= sidebarX + sidebarW
            && mouseY >= groupAreaTop() && mouseY <= groupAreaBottom()) {
            draggingGroupBar = true;
            scrollGroupsTo(mouseY);
            return;
        }

        if (search != null) search.mouseClicked(mouseX, mouseY, mouseButton);

        int entry = sidebarEntryAt(mouseX, mouseY);
        if (entry != Integer.MIN_VALUE) {
            groupMode = entry;
            lastGroupMode = entry;
            scroll = 0;
            rebuild();
            return;
        }

        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) return;
        int index = (mouseY - listY + scroll) / ROW_H;
        if (index < 0 || index >= rows.size()) return;

        MachineRecord rec = rows.get(index);
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);

        if (shift && anchorIndex >= 0) {
            selectRange(anchorIndex, index);
        } else if (ctrl) {
            toggleSelect(rec, index);
        } else {
            selectOnly(rec, index);
        }

        dragRow = index;
        dragX = mouseX;
        dragY = mouseY;
        dragging = false;

        long now = System.currentTimeMillis();
        boolean doubleClick = !ctrl && !shift && clickRow == index && now - clickAt < 350L;
        clickRow = index;
        clickAt = now;
        if (doubleClick) trackSelected();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingListBar) {
            scrollListTo(mouseY);
            return;
        }
        if (draggingGroupBar) {
            scrollGroupsTo(mouseY);
            return;
        }
        if (dragRow >= 0 && clickedMouseButton == 0) {
            if (Math.abs(mouseX - dragX) + Math.abs(mouseY - dragY) > 4) dragging = true;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state == 0) {
            draggingListBar = false;
            draggingGroupBar = false;
        }
        if (state == 0 && dragging) {
            int entry = sidebarEntryAt(mouseX, mouseY);
            if (entry == -2) {
                unassignSelection();
            } else if (entry >= 0 && entry < MachineGroups.GROUPS.size()) {
                assignTo(MachineGroups.GROUPS.get(entry));
            }
            dragging = false;
            dragRow = -1;
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    /** @return Integer.MIN_VALUE = 没点中左栏；否则 -1 全部 / -2 未分组 / >=0 分组下标 */
    private int sidebarEntryAt(int mouseX, int mouseY) {
        if (mouseX < sidebarX || mouseX >= sidebarX + sidebarW) return Integer.MIN_VALUE;
        if (mouseY < groupListTop) return Integer.MIN_VALUE;

        int y = groupListTop;
        if (mouseY < y + GROUP_ROW_H) return -1;
        y += GROUP_ROW_H;
        if (mouseY < y + GROUP_ROW_H) return -2;

        int areaTop = groupAreaTop();
        if (mouseY < areaTop) return Integer.MIN_VALUE;
        y = areaTop - groupScroll;
        for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
            if (y + GROUP_ROW_H > groupAreaBottom()) break;
            if (mouseY >= y && mouseY < y + GROUP_ROW_H && mouseY >= areaTop) return i;
            y += GROUP_ROW_H;
        }
        return Integer.MIN_VALUE;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        // LWJGL3（lwjgl3ify）下 DWheel 常常是 ±1 而不是 ±120，两种都要认
        int steps = wheel / 120;
        if (steps == 0) steps = wheel > 0 ? 1 : -1;

        if (inSidebar(lastMouseX, lastMouseY)) {
            groupScroll -= steps * GROUP_ROW_H;
            clampGroupScroll();
        } else {
            scroll -= steps * ROW_H;
            clampScroll();
        }
    }

    private boolean inSidebar(int mouseX, int mouseY) {
        return mouseX >= sidebarX && mouseX < sidebarX + sidebarW
            && mouseY >= groupAreaTop()
            && mouseY < groupAreaBottom();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 900:
                trackSelected();
                return;
            case 901:
                mc.displayGuiScreen(null);
                return;
            case 902:
                filter = (filter + 1) % 3;
                RadarState.layer = filter;
                RadarConfig.saveLayer(filter);
                button.displayString = filterLabel();
                scroll = 0;
                rebuild();
                return;
            case 903:
                sortMode = (sortMode + 1) % SORT_NAMES.length;
                button.displayString = sortLabel();
                rebuild();
                return;
            case 904:
                assignSelected();
                return;
            case 905:
                unassignSelection();
                return;
            case 906:
                createGroup();
                return;
            case 907:
                mc.displayGuiScreen(new GuiMachineMap());
                return;
            case 908: {
                MachineRecord rec = primaryRec();
                if (rec != null) {
                    mc.displayGuiScreen(new GuiEfficiency(rec));
                } else {
                    MachineGroup group = selectedGroup();
                    if (group != null) mc.displayGuiScreen(new GuiEfficiency(group));
                    else say("先选中一台机器，或在左栏选一个分组看分组心电图");
                }
                return;
            }
            case 909:
                toggleWorking();
                return;
            case 910:
                RadarConfig.groupPreviewMode = (RadarConfig.groupPreviewMode + 1) % 3;
                RadarConfig.save();
                button.displayString = areaLabel();
                return;
            case 920:
                RadarConfig.statsGroupedOnly = !RadarConfig.statsGroupedOnly;
                RadarConfig.save();
                button.displayString = statsLabel();
                return;
            case 921:
                RadarConfig.statsIncremental = !RadarConfig.statsIncremental;
                RadarConfig.save();
                button.displayString = incrementalLabel();
                return;
            case 922:
                debug = !debug;
                button.displayString = debugLabel();
                return;
            case 923:
                startRegionTool();
                return;
            case 929:
                analysisMode = (analysisMode + 1) % 3;
                layoutDirty = true;
                return;
            case 930:
                statWindow = (statWindow + 1) % 3;
                button.displayString = windowLabel();
                return;
            case 931:
                analysisMode = analysisMode == 2 ? 1 : 2;
                layoutDirty = true;
                return;
            default:
                break;
        }
    }

    private static String filterLabel() {
        return "图层：" + RadarState.layerName(RadarState.layer);
    }

    private String sortLabel() {
        return "排序：" + SORT_NAMES[sortMode];
    }

    private static String statsLabel() {
        return RadarConfig.statsGroupedOnly ? "统计：分组" : "统计：全局";
    }

    private static String incrementalLabel() {
        return RadarConfig.statsIncremental ? "分帧：开" : "分帧：关";
    }

    private static String debugLabel() {
        return debug ? "调试：开" : "调试：关";
    }

    private static String areaLabel() {
        switch (RadarConfig.groupPreviewMode) {
            case 1:
                return "区域：分组框";
            case 2:
                return "区域：区块预览";
            default:
                return "区域：关";
        }
    }

    private static String analysisLabel() {
        switch (analysisMode) {
            case 1:
                return "分析：全局";
            case 2:
                return "分析：分组";
            default:
                return "分析：关";
        }
    }

    private static String windowLabel() {
        return "窗口：" + MachineHistory.windowName(statWindow);
    }

    private static String scopeLabel() {
        return analysisMode == 2 ? "范围：分组" : "范围：全局";
    }

    private GuiButton buttonById(int id) {
        for (int i = 0; i < buttonList.size(); i++) {
            GuiButton b = buttonList.get(i);
            if (b.id == id) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (mc == null || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        if (layoutDirty) {
            layoutDirty = false;
            initGui();
        }
        if (RadarState.snapshot != lastSnapshot) rebuild();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        px = mc.thePlayer.posX;
        py = mc.thePlayer.posY;
        pz = mc.thePlayer.posZ;

        FactoryStats.tick(
            mc.thePlayer.dimension,
            selectedGroup(),
            RadarConfig.statsGroupedOnly,
            RadarConfig.statsIncremental);

        drawDefaultBackground();

        drawRect(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xC0000000);
        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xE8101418);
        drawRect(panelX, panelY, panelX + panelW, panelY + 1, 0xFF3A6EA5);

        drawHeader();
        drawSidebar(mouseX, mouseY);
        drawRows(mouseX, mouseY);
        drawScrollbar();
        drawGroupScrollbar();

        if (search != null) search.drawTextBox();

        drawDetail();
        if (analysisMode > 0) drawAnalysis();

        GuiButton assign = buttonById(904);
        if (assign != null) assign.enabled = !selection.isEmpty();
        GuiButton unassign = buttonById(905);
        if (unassign != null) unassign.enabled = anySelectedGrouped();
        GuiButton work = buttonById(909);
        if (work != null) {
            work.enabled = !selection.isEmpty();
            MachineRecord rec = primaryRec();
            work.displayString = rec == null ? "开关" : (rec.allowed ? "关闭" : "开启");
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (dragging) {
            String hint = "拖到左栏分组上以归组";
            int w = fontRendererObj.getStringWidth(hint);
            drawRect(mouseX + 8, mouseY + 8, mouseX + 16 + w, mouseY + 24, 0xC0000000);
            drawString(fontRendererObj, hint, mouseX + 12, mouseY + 12, 0xFFFFFF);
        }
    }

    private boolean anySelectedGrouped() {
        List<MachineRecord> sel = selectedMachines();
        for (int i = 0; i < sel.size(); i++) {
            if (MachineGroups.groupOf(sel.get(i)) != null) return true;
        }
        return false;
    }

    private void drawHeader() {
        drawCenteredString(fontRendererObj, "Machine Radar · 工厂列表", width / 2, panelY + 8, 0xFFFFFF);

        FactoryStats.Data st = FactoryStats.data();
        StringBuilder line = new StringBuilder();
        line.append("统计(")
            .append(st.scope)
            .append(")")
            .append(FactoryStats.sweeping() ? "…" : "")
            .append(" 共 ")
            .append(st.total)
            .append(" · 运行 ")
            .append(st.running)
            .append(" · 待机 ")
            .append(st.idle)
            .append(" · 未成型 ")
            .append(st.unformed)
            .append(" · 单方块 ")
            .append(st.single)
            .append(st.genEu > 0 ? " · 发电 " + RadarText.fmt(st.genEu) : "")
            .append(" · 列表 ")
            .append(rows.size());

        drawCenteredString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, line.toString(), panelW - PAD * 2),
            width / 2,
            panelY + 20,
            0x90A4AE);

        // 调试信息单独一行，不再和统计挤在同一行
        if (debug) drawDebugLine(st);
    }

    private void drawDebugLine(FactoryStats.Data st) {
        StringBuilder d = new StringBuilder("调试");
        d.append(String.format(Locale.ROOT, " %.2fms", st.nanos / 1_000_000.0D))
            .append(" · 选中 ")
            .append(selection.size())
            .append(" · 分组 ")
            .append(MachineGroups.GROUPS.size());
        if (FactoryStats.sweeping()) {
            d.append(" · 分帧 ")
                .append(FactoryStats.processed())
                .append("/")
                .append(FactoryStats.sourceSize());
        }
        if (RegionSelection.isComplete()) {
            d.append(" · 选区 ")
                .append(RegionSelection.sizeText())
                .append(" 内 ")
                .append(RegionSelection.countMachines(mc.thePlayer.dimension))
                .append(" 台");
        } else if (RegionSelection.isActive()) {
            d.append(" · 选区 未完成");
        }
        drawCenteredString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, d.toString(), panelW - PAD * 2),
            width / 2,
            panelY + 33,
            0x607D8B);
    }

    private void drawSidebar(int mouseX, int mouseY) {
        drawRect(sidebarX - 6, sidebarY - 4, sidebarX + sidebarW + 4, sidebarY + sidebarH, 0x40000000);
        drawString(fontRendererObj, "分组", sidebarX, sidebarY, 0xB0BEC5);

        RenderHelper.enableGUIStandardItemLighting();
        drawSidebarRow(-1, groupListTop, "全部机器", null, 0xFFFFFF, Integer.toString(countAll), countAll > 0, mouseX, mouseY);
        drawSidebarRow(
            -2,
            groupListTop + GROUP_ROW_H,
            "未分组",
            null,
            0x90A4AE,
            Integer.toString(countUngrouped),
            countUngrouped > 0,
            mouseX,
            mouseY);

        int areaTop = groupAreaTop();
        int y = areaTop - groupScroll;
        drawRect(sidebarX, areaTop - 3, sidebarX + sidebarW, areaTop - 2, 0x30FFFFFF);

        for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
            if (y + GROUP_ROW_H > groupAreaBottom()) break;
            if (y >= areaTop && y + GROUP_ROW_H <= groupAreaBottom()) {
                MachineGroup group = MachineGroups.GROUPS.get(i);
                String count = groupOnline[i] + "/" + group.size();
                drawSidebarRow(i, y, group.name, group, group.color, count, groupOnline[i] > 0, mouseX, mouseY);
            }
            y += GROUP_ROW_H;
        }
        RenderHelper.disableStandardItemLighting();

        drawString(fontRendererObj, "右键分组改名/图标", sidebarX, sidebarY + sidebarH - 33, 0x607D8B);
    }

    private void drawSidebarRow(int entry, int y, String name, MachineGroup group, int color, String countText,
        boolean online, int mouseX, int mouseY) {
        boolean selected = groupMode == entry;
        boolean hover = mouseX >= sidebarX && mouseX < sidebarX + sidebarW && mouseY >= y && mouseY < y + GROUP_ROW_H;
        if (selected) drawRect(sidebarX, y, sidebarX + sidebarW, y + GROUP_ROW_H - 1, 0x503F6FA5);
        else if (hover) drawRect(sidebarX, y, sidebarX + sidebarW, y + GROUP_ROW_H - 1, 0x20FFFFFF);

        ItemStack icon = group == null ? null : group.iconStack();
        int textX = sidebarX + 7;
        if (icon != null) {
            RenderItem.getInstance()
                .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, sidebarX + 1, y);
            textX = sidebarX + 19;
        } else if (entry >= 0) {
            drawRect(sidebarX + 1, y + 4, sidebarX + 4, y + GROUP_ROW_H - 4, 0xFF000000 | color);
        } else {
            drawRect(sidebarX + 1, y + 4, sidebarX + 3, y + GROUP_ROW_H - 4, 0xFF546E7A);
        }

        int countW = fontRendererObj.getStringWidth(countText);
        fontRendererObj.drawStringWithShadow(
            countText,
            sidebarX + sidebarW - 3 - countW,
            y + 3,
            online ? 0x81C784 : 0x78909C);
        String label = RadarText.trim(
            fontRendererObj,
            name,
            Math.max(10, sidebarX + sidebarW - 3 - countW - textX - 2));
        fontRendererObj.drawStringWithShadow(label, textX, y + 3, selected ? 0xFFFFFF : 0xCFD8DC);
    }

    private void drawRows(int mouseX, int mouseY) {
        if (rows.isEmpty()) {
            drawCenteredString(
                fontRendererObj,
                RadarState.snapshot.isEmpty() ? "扫描中…（第一次扫描约 1 秒）" : "没有匹配的机器",
                listX + listW / 2,
                listY + 8,
                0x78909C);
            return;
        }

        RenderHelper.enableGUIStandardItemLighting();
        int first = scroll / ROW_H;
        int last = Math.min(rows.size(), first + listH / ROW_H + 1);
        for (int i = first; i < last; i++) {
            int y = listY + i * ROW_H - scroll;
            // 只画完整落在列表区域内的行，避免滚动时糊到搜索框/边框上
            if (y < listY || y + ROW_H > listY + listH) continue;
            MachineRecord rec = rows.get(i);
            boolean primary = isPrimary(rec);
            boolean selected = isSelected(rec);
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H;
            drawRow(rec, y, selected, primary, hover);
        }
        RenderHelper.disableStandardItemLighting();
    }

    private void drawRow(MachineRecord rec, int y, boolean selected, boolean primary, boolean hover) {
        int bg = primary ? 0x603F6FA5 : (selected ? 0x403F6FA5 : (hover ? 0x30FFFFFF : 0));
        if (bg != 0) drawRect(listX, y, listX + listW, y + ROW_H - 1, bg);
        if (!selected && (y / ROW_H) % 2 == 0) drawRect(listX, y, listX + listW, y + ROW_H - 1, 0x10FFFFFF);

        int left = listX + 4;
        MachineGroup group = MachineGroups.groupOf(rec);
        if (group != null) {
            drawRect(listX, y, listX + 2, y + ROW_H - 1, 0xFF000000 | group.color);
            ItemStack icon = group.iconStack();
            if (icon != null) {
                RenderItem.getInstance()
                    .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, left, y);
                left += 18;
            }
        }

        // 状态点
        drawRect(left + 2, y + 7, left + 6, y + 11, 0xFF000000 | rec.colorOf());
        left += 8;

        ItemStack icon = rec.icon;
        if (icon != null) {
            RenderItem.getInstance()
                .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, left, y);
        }
        int textX = left + 18;

        int right = listX + listW - 4;
        String dist = RadarText.dist(rec.x + 0.5D, rec.y + 0.5D, rec.z + 0.5D, px, py, pz);
        int distW = fontRendererObj.getStringWidth(dist);
        fontRendererObj.drawStringWithShadow(dist, right - distW, y + 5, 0x9E9E9E);
        right -= distW + 6;

        String tier = rec.tier >= 0 ? HatchRef.tierName(rec.tier) : "?";
        int tierW = fontRendererObj.getStringWidth(tier);
        fontRendererObj.drawStringWithShadow(tier, right - tierW, y + 5, HatchRef.tierColor(rec.tier));
        right -= Math.max(22, tierW) + 6;

        String state = rec.statusText();
        int stateW = fontRendererObj.getStringWidth(state);
        fontRendererObj.drawStringWithShadow(state, right - stateW, y + 5, rec.colorOf());
        right -= Math.max(30, stateW) + 6;

        if (rec.multi) {
            String hatches = rec.hatches.length + "仓";
            int hw = fontRendererObj.getStringWidth(hatches);
            fontRendererObj.drawStringWithShadow(hatches, right - hw, y + 5, 0x78909C);
            right -= Math.max(28, hw) + 6;
        } else if (rec.storage != null && (rec.storage.chest || rec.storage.tank)) {
            String tag = rec.storage.chest ? "存储箱" : "存储缸";
            int tw = fontRendererObj.getStringWidth(tag);
            fontRendererObj.drawStringWithShadow(tag, right - tw, y + 5, 0x4DD0E1);
            right -= tw + 6;
        }

        String name = RadarText.trim(fontRendererObj, rec.name, Math.max(24, right - textX));
        fontRendererObj.drawStringWithShadow(name, textX, y + 5, primary ? 0xFFFFFF : (selected ? 0xE0E0E0 : 0xB0BEC5));
    }

    // ------------------------------------------------------------------ 滚动条

    private int listBarX() {
        return listX + listW - 3;
    }

    private boolean listBarVisible() {
        return listH > 0 && rows.size() * ROW_H > listH;
    }

    private int listThumbH() {
        int content = rows.size() * ROW_H;
        return Math.max(12, listH * listH / Math.max(1, content));
    }

    private int listMaxScroll() {
        return Math.max(0, rows.size() * ROW_H - listH);
    }

    private void drawScrollbar() {
        if (!listBarVisible()) return;
        int barX = listBarX();
        drawRect(barX, listY, barX + 3, listY + listH, 0x30FFFFFF);
        int thumbH = listThumbH();
        int thumbY = listY + (listH - thumbH) * scroll / Math.max(1, listMaxScroll());
        drawRect(barX, thumbY, barX + 3, thumbY + thumbH, draggingListBar ? 0xD0C6E0FF : 0x90A0B0C0);
    }

    /** 列表滑块拖动：把鼠标 y 映射成 scroll */
    private void scrollListTo(int mouseY) {
        if (!listBarVisible()) return;
        int thumbH = listThumbH();
        int track = listH - thumbH;
        int maxScroll = listMaxScroll();
        if (track <= 0) {
            scroll = 0;
            return;
        }
        int rel = mouseY - listY - thumbH / 2;
        if (rel < 0) rel = 0;
        if (rel > track) rel = track;
        // 按行对齐，避免出现半行把搜索框/边框糊住
        scroll = rel * maxScroll / track / ROW_H * ROW_H;
        clampScroll();
    }

    private int groupAreaTop() {
        return groupListTop + GROUP_ROW_H * 2 + 6;
    }

    private int groupAreaBottom() {
        return sidebarY + sidebarH - 26;
    }

    private int groupContentH() {
        return MachineGroups.GROUPS.size() * GROUP_ROW_H;
    }

    private int groupViewH() {
        return Math.max(0, groupAreaBottom() - groupAreaTop());
    }

    private boolean groupBarVisible() {
        return groupViewH() > 0 && groupContentH() > groupViewH();
    }

    private int groupThumbH() {
        return Math.max(10, groupViewH() * groupViewH() / Math.max(1, groupContentH()));
    }

    private int groupMaxScroll() {
        return Math.max(0, groupContentH() - groupViewH());
    }

    private void clampGroupScroll() {
        if (groupScroll > groupMaxScroll()) groupScroll = groupMaxScroll();
        if (groupScroll < 0) groupScroll = 0;
    }

    private void drawGroupScrollbar() {
        if (!groupBarVisible()) return;
        int barX = sidebarX + sidebarW - 3;
        int top = groupAreaTop();
        int viewH = groupViewH();
        drawRect(barX, top, barX + 3, top + viewH, 0x30FFFFFF);
        int thumbH = groupThumbH();
        int thumbY = top + (viewH - thumbH) * groupScroll / Math.max(1, groupMaxScroll());
        drawRect(barX, thumbY, barX + 3, thumbY + thumbH, draggingGroupBar ? 0xD0C6E0FF : 0x90A0B0C0);
    }

    private void scrollGroupsTo(int mouseY) {
        if (!groupBarVisible()) return;
        int viewH = groupViewH();
        int thumbH = groupThumbH();
        int track = viewH - thumbH;
        if (track <= 0) {
            groupScroll = 0;
            return;
        }
        int rel = mouseY - groupAreaTop() - thumbH / 2;
        if (rel < 0) rel = 0;
        if (rel > track) rel = track;
        groupScroll = rel * groupMaxScroll() / track / GROUP_ROW_H * GROUP_ROW_H;
        clampGroupScroll();
    }

    // ------------------------------------------------------------------ 分析面板

    /**
     * 右侧「分析」面板：全局 / 分组预览 + 效率曲线 + 秒/分钟/小时统计。
     *
     * <p>打开时页面会加宽并让出这一列（见 {@link #initGui()}），不会盖住列表和详情。
     */
    private void drawAnalysis() {
        int x = analysisX;
        int y = analysisY;
        int w = analysisW;
        int bottom = analysisY + analysisH;

        drawRect(x - 4, y - 4, x + w + 4, bottom + 4, 0x70000000);
        drawString(fontRendererObj, analysisMode == 2 ? "分析 · 分组预览" : "分析 · 全局预览", x, y, 0xCFD8DC);

        int gx = x + 4;
        int gy = y + 36;
        int gw = w - 8;
        int gh = Math.min(84, Math.max(54, analysisH / 4));
        int dim = mc.thePlayer.dimension;

        MachineHistory.GroupTrace trace;
        MachineHistory.WindowStats stats;
        MachineGroup group = selectedGroup();

        if (analysisMode == 2) {
            if (group == null) {
                EcgPlot.frame(fontRendererObj, gx, gy, gw, gh);
                EcgPlot.empty(fontRendererObj, gx, gy, gw, gh, "先在左栏选一个分组");
                drawString(fontRendererObj, "窗口：" + MachineHistory.windowName(statWindow), gx, gy + gh + 6, 0x78909C);
                return;
            }
            trace = MachineHistory.trace(group, dim);
            stats = MachineHistory.groupWindowStats(group, dim, statWindow);
        } else {
            trace = MachineHistory.traceAll(dim);
            stats = MachineHistory.globalWindowStats(dim, statWindow);
        }

        EcgPlot.trace(fontRendererObj, gx, gy, gw, gh, trace);

        int sy = gy + gh + 6;
        drawString(
            fontRendererObj,
            "台数 " + stats.devices + " · 运行 " + stats.runningNo + " · 待机 " + stats.idleNo + " · 未成型 "
                + stats.unformedNo,
            gx,
            sy,
            0xB0BEC5);
        sy += 10;

        String ratio = stats.runRatio < 0 ? "?" : Math.round(stats.runRatio * 100) + "%";
        drawString(
            fontRendererObj,
            "运行率 " + ratio + "（" + MachineHistory.windowName(statWindow) + "平均）",
            gx,
            sy,
            0x90A4AE);
        sy += 10;

        String out = stats.outputPerSecond > 0 ? String.format(Locale.ROOT, "%.2f/s", stats.outputPerSecond) : "-";
        drawString(
            fontRendererObj,
            "合计 " + RadarText.fmt(stats.eut) + " EU/t · 产出 " + out,
            gx,
            sy,
            0x90A4AE);
        sy += 12;

        if (analysisMode == 1) {
            drawString(fontRendererObj, "打组预览（点左栏看单组）", gx, sy, 0x78909C);
            sy += 11;
            for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
                if (sy > bottom - 10) break;
                MachineGroup g = MachineGroups.GROUPS.get(i);
                MachineHistory.WindowStats gs = MachineHistory.groupWindowStats(g, dim, statWindow);
                String gRatio = gs.runRatio < 0 ? "?" : Math.round(gs.runRatio * 100) + "%";
                drawRect(gx, sy + 3, gx + 4, sy + 7, 0xFF000000 | g.color);
                String line = g.name + "  " + MachineGroups.onlineCount(g, dim) + "/" + g.size() + "  " + gRatio + "  "
                    + RadarText.fmt(gs.eut) + " EU/t";
                drawString(fontRendererObj, RadarText.trim(fontRendererObj, line, gw - 8), gx + 7, sy, 0xB0BEC5);
                sy += 10;
            }
        } else {
            ProductionLedger.GroupLedger gl = ProductionLedger.ledger(group);
            List<ProductionLedger.Flow> flows = ProductionLedger.flows(group, statWindow);
            if (!flows.isEmpty()) {
                String head = "资源（" + ProductionLedger.windowName(statWindow) + "净产出）";
                if (gl != null) head += " · 配方 " + (int) gl.completions(statWindow);
                drawString(fontRendererObj, RadarText.trim(fontRendererObj, head, gw - 8), gx, sy, 0x78909C);
                sy += 11;
                for (int i = 0; i < flows.size() && i < 8 && sy <= bottom - 10; i++) {
                    ProductionLedger.Flow f = flows.get(i);
                    long net = ProductionLedger.net(f, statWindow);
                    String line = (f.fluid ? "流体 " : "") + f.name + "  " + (net >= 0 ? "+" : "") + RadarText.fmt(net);
                    drawString(
                        fontRendererObj,
                        RadarText.trim(fontRendererObj, line, gw - 8),
                        gx,
                        sy,
                        net >= 0 ? 0x81C784 : 0xFF8A80);
                    sy += 10;
                }
            } else {
                drawString(fontRendererObj, "成员 · " + group.name, gx, sy, 0x78909C);
                sy += 11;
                List<MachineRecord> members = MachineGroups.onlineMembers(group);
                for (int i = 0; i < members.size(); i++) {
                    if (sy > bottom - 10) break;
                    MachineRecord rec = members.get(i);
                    drawRect(gx, sy + 3, gx + 4, sy + 7, 0xFF000000 | rec.colorOf());
                    String line = rec.name + "  " + rec.statusText() + "  "
                        + RadarText.fmt(MachineHistory.statEut(rec, statWindow)) + " EU/t";
                    drawString(fontRendererObj, RadarText.trim(fontRendererObj, line, gw - 8), gx + 7, sy, 0xB0BEC5);
                    sy += 10;
                }
            }
        }
    }

    /** "入 A×2 + B×1" / "出 C×1"；没有该项返回空串 */
    private static String recipeSide(String label, String[] items, long[] itemAmounts, String[] fluids,
        long[] fluidAmounts) {
        StringBuilder sb = new StringBuilder();
        if (items != null) {
            for (int i = 0; i < items.length; i++) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(items[i])
                    .append('×')
                    .append(itemAmounts[i]);
            }
        }
        if (fluids != null) {
            for (int i = 0; i < fluids.length; i++) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(fluids[i])
                    .append(' ')
                    .append(fluidAmounts[i])
                    .append("mB");
            }
        }
        return sb.length() == 0 ? "" : label + " " + sb;
    }

    private void drawDetail() {
        MachineRecord rec = primaryRec();
        int x = detailX;
        int y = detailY;
        int w = detailW;

        drawRect(x - 6, y - 4, x + w + 4, y + listH + 26, 0x70000000);

        if (rec == null) {
            drawString(fontRendererObj, "选择左侧机器查看详情（Ctrl/Shift 多选）", x, y + 4, 0x90A4AE);
            drawString(fontRendererObj, "拖到左栏分组 / Ctrl+A 全选", x, y + 18, 0x607D8B);
            drawString(fontRendererObj, "底部可以远程开关机 / 心电图 / 地图", x, y + 32, 0x607D8B);
            int ty = y + 52;
            if (RadarConfig.groupPreviewMode > 0) {
                drawString(fontRendererObj, "区域预览（占位）", x, ty, 0xCFD8DC);
                ty += 14;
                for (int i = 0; i < MachineGroups.GROUPS.size() && ty < y + listH - 10; i++) {
                    MachineGroup group = MachineGroups.GROUPS.get(i);
                    int[] b = MachineGroups.bounds(group, mc.thePlayer.dimension);
                    if (b == null) continue;
                    int chunksX = ((b[3] - b[0] + 16) >> 4);
                    int chunksZ = ((b[5] - b[2] + 16) >> 4);
                    drawRect(x, ty + 3, x + 4, ty + 7, 0xFF000000 | group.color);
                    drawString(
                        fontRendererObj,
                        group.name + "  " + MachineGroups.onlineCount(group, mc.thePlayer.dimension) + "台  " + chunksX + "×" + chunksZ + "区块",
                        x + 7,
                        ty,
                        0xB0BEC5);
                    ty += 11;
                }
            }
            return;
        }

        int tx = x;
        int ty = y;

        fontRendererObj.drawStringWithShadow(RadarText.trim(fontRendererObj, rec.name, w - 20), tx, ty, rec.colorOf());
        ty += 11;
        fontRendererObj.drawStringWithShadow(
            RadarText.trim(fontRendererObj, "ID " + rec.id + " · " + rec.metaName, w - 20),
            tx,
            ty,
            0x78909C);
        ty += 12;

        String dist = RadarText.dist(rec.x + 0.5D, rec.y + 0.5D, rec.z + 0.5D, px, py, pz);
        drawString(
            fontRendererObj,
            RadarText.trim(
                fontRendererObj,
                "维度 " + rec.dim + " · " + rec.x + ", " + rec.y + ", " + rec.z + " · " + dist,
                w - 12),
            tx,
            ty,
            0xB0BEC5);
        ty += 11;

        String tier = rec.tier >= 0 ? HatchRef.tierName(rec.tier) : "?";
        drawString(
            fontRendererObj,
            RadarText.trim(
                fontRendererObj,
                tier + " · " + rec.statusText() + (rec.multi ? (rec.assembled ? " · 已成型" : " · 未成型") : ""),
                w - 12),
            tx,
            ty,
            rec.allowed ? 0xB0BEC5 : 0x9E9E9E);
        ty += 11;

        MachineGroup group = MachineGroups.groupOf(rec);
        drawRect(tx, ty + 2, tx + 4, ty + 8, 0xFF000000 | (group == null ? 0x757575 : group.color));
        drawString(
            fontRendererObj,
            RadarText.trim(
                fontRendererObj,
                "分组 " + (group == null ? "未分组" : group.name) + (rec.allowed ? "" : " · 已停机"),
                w - 12),
            tx + 7,
            ty,
            group == null ? 0x757575 : group.color);
        ty += 11;

        if (group != null && group.hasRegion(rec.dim)) {
            drawString(
                fontRendererObj,
                "区域 " + group.regionSizeText() + "（框选定义，非成员包围盒）",
                tx,
                ty,
                0x4DD0E1);
            ty += 11;
        }

        // 存储机器（量子箱/量子缸）内容
        if (rec.storage != null && (rec.storage.itemCount > 0 || rec.storage.fluidAmount > 0 || rec.storage.chest
            || rec.storage.tank)) {
            String st;
            if (rec.storage.chest) {
                st = "存储 " + (rec.storage.itemName == null ? "空" : rec.storage.itemName)
                    + " ×"
                    + RadarText.fmt(rec.storage.itemCount)
                    + (rec.storage.itemCapacity > 0 ? " / " + RadarText.fmt(rec.storage.itemCapacity) : "");
            } else {
                st = "存储 " + (rec.storage.fluidName == null ? "空" : rec.storage.fluidName)
                    + " "
                    + RadarText.fmt(rec.storage.fluidAmount)
                    + " mB"
                    + (rec.storage.fluidCapacity > 0 ? " / " + RadarText.fmt(rec.storage.fluidCapacity) : "");
            }
            drawString(fontRendererObj, RadarText.trim(fontRendererObj, st, w - 12), tx, ty, 0x4DD0E1);
            ty += 11;
        }

        // 当前配方：输入一行、输出+时长一行，避免一行太长被截
        if (rec.hasRecipe()) {
            MachineRecord.RecipeSummary rp = rec.recipe;
            String in = recipeSide("入", rp.inItemNames, rp.inItemAmounts, rp.inFluidNames, rp.inFluidAmounts);
            String out = recipeSide("出", rp.outItemNames, rp.outItemAmounts, rp.outFluidNames, rp.outFluidAmounts);
            if (in.length() > 0) {
                drawString(fontRendererObj, RadarText.trim(fontRendererObj, "配方 " + in, w - 12), tx, ty, 0xB0BEC5);
                ty += 10;
            }
            String meta = "  " + out + " · " + String.format(Locale.ROOT, "%.1fs", rp.durationTicks / 20.0D) + " · "
                + RadarText.fmt(Math.abs(rp.eut)) + " EU/t";
            drawString(fontRendererObj, RadarText.trim(fontRendererObj, meta, w - 12), tx, ty, 0x90A4AE);
            ty += 11;
        }

        if (rec.multi) {
            String struct = rec.blockCount > 0 ? rec.blockCount + " 块结构" + (rec.bboxExact ? "（精确）" : "")
                : "结构未知" + (rec.bboxError.isEmpty() ? "" : "：" + rec.bboxError);
            drawString(fontRendererObj, struct, tx, ty, rec.blockCount > 0 ? 0x81C784 : 0xFF8A80);
        } else {
            drawString(fontRendererObj, "单方块机器（无结构 / 舱室）", tx, ty, 0x90A4AE);
        }
        ty += 12;

        // 进度条
        int barY = ty + 2;
        drawRect(tx, barY, tx + w - 12, barY + 5, 0x40FFFFFF);
        if (rec.progress >= 0) {
            drawRect(tx, barY, tx + (w - 12) * rec.progress / 1000, barY + 5, 0xFF66FF66);
        }
        ty += 10;
        String progress = rec.progress >= 0
            ? "进度 " + (rec.progress / 10)
                + "% · "
                + String.format(
                    Locale.ROOT,
                    "%.1fs / %.1fs",
                    rec.progressTicks / 20.0D,
                    rec.recipeSeconds())
            : "无进行中的配方";
        drawString(fontRendererObj, RadarText.trim(fontRendererObj, progress, w - 12), tx, ty, 0xCFD8DC);
        ty += 12;

        StringBuilder eu = new StringBuilder();
        if (rec.eut != 0) eu.append(rec.eut > 0 ? "耗电 " : "发电 ")
            .append(RadarText.fmt(Math.abs(rec.eut)))
            .append(" EU/t");
        if (rec.maxEuInput > 0) {
            if (eu.length() > 0) eu.append(" · ");
            eu.append("上限 ")
                .append(RadarText.fmt(rec.maxEuInput))
                .append("V/")
                .append(rec.maxAmps)
                .append("A");
        }
        if (eu.length() > 0) {
            drawString(fontRendererObj, RadarText.trim(fontRendererObj, eu.toString(), w - 12), tx, ty, 0xB0BEC5);
            ty += 11;
        }

        if (rec.outputName != null && !rec.outputName.isEmpty()) {
            String out = "产出 " + rec.outputName + " ×" + rec.outputAmount
                + (rec.outputPerSecond() > 0 ? String.format(Locale.ROOT, " · %.2f/s", rec.outputPerSecond()) : "");
            drawString(fontRendererObj, RadarText.trim(fontRendererObj, out, w - 12), tx, ty, 0xE0E0E0);
            ty += 11;
        }

        if (rec.multi && rec.hasMaintenance) {
            String maint = rec.maintenanceMissing > 0 ? "维护 缺 " + rec.maintenanceMissing + " 项" : "维护 正常";
            drawString(fontRendererObj, maint, tx, ty, rec.maintenanceMissing > 0 ? 0xFF8A80 : 0x81C784);
            ty += 11;
        }

        // 输入/输出总线与仓的计数
        if (rec.multi && ty < y + listH - 46) {
            long itemIn = rec.hatchAmount(HatchRef.ITEM_IN);
            long itemOut = rec.hatchAmount(HatchRef.ITEM_OUT);
            int usedIn = rec.hatchUsedSlots(HatchRef.ITEM_IN);
            int totalIn = rec.hatchTotalSlots(HatchRef.ITEM_IN);
            int usedOut = rec.hatchUsedSlots(HatchRef.ITEM_OUT);
            int totalOut = rec.hatchTotalSlots(HatchRef.ITEM_OUT);
            if (totalIn + totalOut > 0) {
                String items = "物品 入 " + RadarText.fmt(itemIn) + " (" + usedIn + "/" + totalIn + "槽)"
                    + "  出 " + RadarText.fmt(itemOut) + " (" + usedOut + "/" + totalOut + "槽)";
                drawString(fontRendererObj, RadarText.trim(fontRendererObj, items, w - 12), tx, ty, 0xB0BEC5);
                ty += 11;
            }

            long fluidIn = rec.hatchAmount(HatchRef.FLUID_IN);
            long fluidInCap = rec.hatchCapacity(HatchRef.FLUID_IN);
            long fluidOut = rec.hatchAmount(HatchRef.FLUID_OUT);
            long fluidOutCap = rec.hatchCapacity(HatchRef.FLUID_OUT);
            if (fluidInCap + fluidOutCap > 0 || fluidIn + fluidOut > 0) {
                String fluids = "流体 入 " + RadarText.fmt(fluidIn) + "/" + RadarText.fmt(fluidInCap)
                    + "  出 " + RadarText.fmt(fluidOut) + "/" + RadarText.fmt(fluidOutCap) + " mB";
                drawString(fontRendererObj, RadarText.trim(fontRendererObj, fluids, w - 12), tx, ty, 0x90A4AE);
                ty += 11;
            }
        }

        ty = drawHistory(tx, ty, w, rec);

        ty += 2;
        drawRect(tx, ty, tx + w - 12, ty + 1, 0x30FFFFFF);
        ty += 6;

        if (!rec.multi || rec.hatches.length == 0) {
            drawString(fontRendererObj, rec.multi ? "无舱室" : "单方块", tx, ty, 0x757575);
            return;
        }

        drawString(fontRendererObj, "舱室 " + rec.hatches.length, tx, ty, 0xCFD8DC);
        ty += 12;

        for (int kind = 0; kind <= HatchRef.OTHER && ty < y + listH + 10; kind++) {
            int n = rec.hatchCount(kind);
            if (n <= 0) continue;
            drawRect(tx, ty + 3, tx + 5, ty + 8, 0xFF000000 | HatchRef.colorOf(kind));
            String label = HatchRef.nameOf(kind) + " ×" + n;
            drawString(fontRendererObj, label, tx + 9, ty, 0xB0BEC5);
            ty += 11;
        }
    }

    /** 近 1 分钟的历史：运行占比 + 平均耗电 + 迷你柱状图（后续扩成产量曲线） */
    private int drawHistory(int tx, int ty, int w, MachineRecord rec) {
        MachineHistory.Samples samples = MachineHistory.get(rec);
        if (samples == null || samples.size() < 2) return ty;

        double run = MachineHistory.runningFraction(rec);
        int avgEu = MachineHistory.avgEut(rec);
        double out = MachineHistory.avgOutputPerSecond(rec);

        StringBuilder line = new StringBuilder("近1分钟");
        if (run >= 0) line.append(" 运行 ")
            .append(Math.round(run * 100))
            .append('%');
        if (avgEu != 0) line.append(" 平均 ")
            .append(RadarText.fmt(Math.abs(avgEu)))
            .append(" EU/t");
        if (out > 0) line.append(String.format(Locale.ROOT, " 产出 %.2f/s", out));
        drawString(fontRendererObj, RadarText.trim(fontRendererObj, line.toString(), w - 12), tx, ty, 0x90A4AE);
        ty += 11;

        int graphW = w - 14;
        int graphH = 9;
        int bars = Math.min(samples.size(), Math.max(8, graphW / 2));
        int start = samples.size() - bars;
        long maxEu = 1L;
        for (int i = 0; i < bars; i++) {
            maxEu = Math.max(maxEu, Math.abs((long) samples.eut(start + i)));
        }
        int baseY = ty + graphH;
        for (int i = 0; i < bars; i++) {
            int idx = start + i;
            long eu = Math.abs((long) samples.eut(idx));
            int h = (int) Math.max(1L, graphH * eu / maxEu);
            int color = (samples.flags(idx) & 1) != 0 ? 0xFF66FF66 : 0xFF546E7A;
            drawRect(tx + i * 2, baseY - h, tx + i * 2 + 2, baseY, color);
        }
        drawRect(tx, baseY, tx + bars * 2, baseY + 1, 0x30FFFFFF);
        ty += graphH + 4;
        return ty;
    }

    // ------------------------------------------------------------------ 工具

    private double distSq(MachineRecord rec) {
        double dx = rec.x + 0.5D - px;
        double dy = rec.y + 0.5D - py;
        double dz = rec.z + 0.5D - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static int statusRank(MachineRecord rec) {
        switch (rec.status()) {
            case MachineRecord.RUNNING:
                return 0;
            case MachineRecord.IDLE:
                return 1;
            case MachineRecord.UNFORMED:
                return 2;
            default:
                return 3;
        }
    }
}
