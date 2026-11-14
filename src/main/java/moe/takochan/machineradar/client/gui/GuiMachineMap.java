package moe.takochan.machineradar.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import moe.takochan.machineradar.client.MachineGroups;
import moe.takochan.machineradar.client.RadarText;
import moe.takochan.machineradar.config.RadarConfig;
import moe.takochan.machineradar.radar.HatchRef;
import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 2D 俯视机器地图（多图层版）。
 *
 * <p>图层：多方块足迹 / 单方块点 / 电网（能源仓）/ 物流（物品总线）/ 流体仓。
 * 多方块画整机 XZ 足迹，按 Y 从低到高绘制（单轴排序，不做透视）；同位置堆叠时悬停可看
 * 全部 Y。层筛选只显示玩家所在层 ±N，用来把上下堆叠的机器分开。
 *
 * <p>缩放、悬停、单击选中、双击追踪；选中后可以打开效率心电图。
 */
public class GuiMachineMap extends GuiScreen {

    private static final int PAD = 12;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 58;
    private static final double HIT_RADIUS = 7.0D;

    private static final int LAYER_MB = 1;
    private static final int LAYER_SB = 2;
    private static final int LAYER_POWER = 4;
    private static final int LAYER_ITEM = 8;
    private static final int LAYER_FLUID = 16;

    private static final int Y_ALL = 0;
    private static final int Y_8 = 1;
    private static final int Y_16 = 2;

    private static int lastLayers = LAYER_MB | LAYER_SB;
    private static int lastYFilter = Y_ALL;

    private int panelX, panelY, panelW, panelH;
    private int mapX, mapY, mapW, mapH;

    private double centerX, centerZ;
    private double scale = 2.0D;
    private boolean centered;
    private int yCenter;
    private int layerMask = lastLayers;
    private int yFilter = lastYFilter;

    private final List<MachineRecord> visible = new ArrayList<MachineRecord>(256);
    private MachineRecord hover;
    private MachineRecord selected;
    private int clickX = -1, clickY = -1;
    private long clickAt;

    private final Comparator<MachineRecord> byY = new Comparator<MachineRecord>() {

        @Override
        public int compare(MachineRecord a, MachineRecord b) {
            int c = Integer.compare(a.y, b.y);
            if (c != 0) return c;
            return Double.compare(distSq(a), distSq(b));
        }
    };

    @Override
    public void initGui() {
        buttonList.clear();
        MachineGroups.ensureLoaded();

        panelW = Math.min(width - 40, 820);
        if (panelW < 520) panelW = Math.max(420, width - 20);
        panelH = Math.min(height - 40, 480);
        if (panelH < 320) panelH = Math.max(300, height - 20);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        mapX = panelX + PAD;
        mapY = panelY + HEADER_H;
        mapW = panelW - PAD * 2;
        mapH = panelH - HEADER_H - FOOTER_H;

        if (!centered && mc != null && mc.thePlayer != null) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            yCenter = (int) Math.floor(mc.thePlayer.posY);
            scale = fitScale();
            centered = true;
        }

        int row1 = panelY + panelH - FOOTER_H + 4;
        int row2 = row1 + 21;

        buttonList.add(new GuiButton(900, panelX + PAD, row1, 72, 18, "追踪"));
        buttonList.add(new GuiButton(903, panelX + PAD + 78, row1, 72, 18, "心电图"));
        buttonList.add(new GuiButton(902, panelX + PAD + 156, row1, 72, 18, "回列表"));
        buttonList.add(new GuiButton(901, panelX + panelW - PAD - 60, row1, 60, 18, "关闭"));

        int x = panelX + PAD;
        buttonList.add(new GuiButton(910, x, row2, 70, 18, layerLabel(LAYER_MB, "多方块")));
        x += 76;
        buttonList.add(new GuiButton(911, x, row2, 70, 18, layerLabel(LAYER_SB, "单方块")));
        x += 76;
        buttonList.add(new GuiButton(912, x, row2, 60, 18, layerLabel(LAYER_POWER, "电网")));
        x += 66;
        buttonList.add(new GuiButton(913, x, row2, 60, 18, layerLabel(LAYER_ITEM, "物流")));
        x += 66;
        buttonList.add(new GuiButton(914, x, row2, 60, 18, layerLabel(LAYER_FLUID, "流体")));
        x += 66;
        buttonList.add(new GuiButton(915, x, row2, 78, 18, yFilterLabel()));

        RadarState.enabled = true;
    }

    private String layerLabel(int bit, String name) {
        return ((layerMask & bit) != 0 ? "◉ " : "○ ") + name;
    }

    private String yFilterLabel() {
        switch (yFilter) {
            case Y_8:
                return "层：±8";
            case Y_16:
                return "层：±16";
            default:
                return "层：全部";
        }
    }

    private double fitScale() {
        double span = Math.max(32.0D, RadarConfig.radius * 2.0D);
        return Math.max(0.6D, Math.min(mapW, mapH) / span);
    }

    @Override
    public void onGuiClosed() {
        lastLayers = layerMask;
        lastYFilter = yFilter;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (mc == null || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        drawDefaultBackground();

        drawRect(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xC0000000);
        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xE8101418);
        drawRect(panelX, panelY, panelX + panelW, panelY + 1, 0xFF3A6EA5);
        drawRect(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF07090C);

        drawCenteredString(fontRendererObj, "机器地图 · 2D 俯视（按 Y 排序）", width / 2, panelY + 8, 0xFFFFFF);

        drawGridAndAxes();
        drawGroupAreas();
        collectVisible();
        hover = pick(mouseX, mouseY);
        drawMachines();
        drawHatchLayers();
        drawSelection();
        drawPlayer();
        drawLegend();
        drawOverlayInfo();
        drawTooltip(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void collectVisible() {
        visible.clear();
        List<MachineRecord> all = RadarState.snapshot;
        int dim = mc.thePlayer.dimension;
        int radius = RadarConfig.radius;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim != dim) continue;
            if (Math.abs(rec.x + 0.5D - centerX) > radius) continue;
            if (Math.abs(rec.z + 0.5D - centerZ) > radius) continue;
            if (yFilter != Y_ALL && Math.abs(rec.y - yCenter) > (yFilter == Y_8 ? 8 : 16)) continue;
            visible.add(rec);
        }
        Collections.sort(visible, byY);
    }

    private boolean shows(MachineRecord rec) {
        if (rec.multi) return (layerMask & LAYER_MB) != 0;
        return (layerMask & LAYER_SB) != 0;
    }

    private void drawMachines() {
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (!shows(rec)) continue;
            if (rec.multi) drawFootprint(rec);
            else drawSingleDot(rec);
        }
    }

    private void drawFootprint(MachineRecord rec) {
        int left = Math.min(sx(rec.minX), sx(rec.maxX + 1));
        int right = Math.max(sx(rec.minX), sx(rec.maxX + 1));
        int top = Math.min(sy(rec.minZ), sy(rec.maxZ + 1));
        int bottom = Math.max(sy(rec.minZ), sy(rec.maxZ + 1));
        if (right < mapX || left > mapX + mapW || bottom < mapY || top > mapY + mapH) return;

        // 裁剪到地图区域，避免大机器足迹画到标题/按钮上
        left = Math.max(left, mapX);
        right = Math.min(right, mapX + mapW);
        top = Math.max(top, mapY);
        bottom = Math.min(bottom, mapY + mapH);

        MachineGroup group = MachineGroups.groupOf(rec);
        int color = group != null ? group.color : rec.colorOf();
        int fillAlpha = rec == hover || rec == selected ? 0x50 : 0x28;

        drawRect(left, top, right, bottom, (fillAlpha << 24) | color);
        drawRect(left, top, right, top + 1, 0xFF000000 | color);
        drawRect(left, bottom - 1, right, bottom, 0xFF000000 | color);
        drawRect(left, top, left + 1, bottom, 0xFF000000 | color);
        drawRect(right - 1, top, right, bottom, 0xFF000000 | color);

        int cx = sx(rec.x + 0.5D);
        int cy = sy(rec.z + 0.5D);
        if (cx < mapX || cx >= mapX + mapW || cy < mapY || cy >= mapY + mapH) return;
        drawRect(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        drawRect(cx - 1, cy - 1, cx + 2, cy + 2, 0xFF000000 | rec.colorOf());
    }

    private void drawSingleDot(MachineRecord rec) {
        int x = sx(rec.x + 0.5D);
        int y = sy(rec.z + 0.5D);
        if (x < mapX || x >= mapX + mapW || y < mapY || y >= mapY + mapH) return;

        MachineGroup group = MachineGroups.groupOf(rec);
        int color = group != null ? group.color : HatchRef.tierColor(rec.tier);
        int size = rec == hover || rec == selected ? 4 : 2;
        drawRect(x - size, y - size, x + size, y + size, 0xFF000000 | color);
        if (rec.progress >= 0) drawRect(x - 1, y - 1, x + 1, y + 1, 0xFFFFFFFF);
    }

    private void drawHatchLayers() {
        boolean power = (layerMask & LAYER_POWER) != 0;
        boolean item = (layerMask & LAYER_ITEM) != 0;
        boolean fluid = (layerMask & LAYER_FLUID) != 0;
        if (!power && !item && !fluid) return;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(1.0F);
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINES);

        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (!rec.multi || rec.hatches.length == 0) continue;
            int cx = sx(rec.x + 0.5D);
            int cy = sy(rec.z + 0.5D);

            for (int h = 0; h < rec.hatches.length; h++) {
                HatchRef hatch = rec.hatches[h];
                int rgb;
                int alpha;
                if (power && (hatch.kind == HatchRef.ENERGY_IN || hatch.kind == HatchRef.ENERGY_OUT)) {
                    rgb = HatchRef.tierColor(hatch.tier);
                    alpha = hatch.kind == HatchRef.ENERGY_IN ? 0x70 : 0x60;
                } else if (item && (hatch.kind == HatchRef.ITEM_IN || hatch.kind == HatchRef.ITEM_OUT)) {
                    rgb = HatchRef.colorOf(hatch.kind);
                    alpha = 0x50;
                } else if (fluid && (hatch.kind == HatchRef.FLUID_IN || hatch.kind == HatchRef.FLUID_OUT)) {
                    rgb = HatchRef.colorOf(hatch.kind);
                    alpha = 0x50;
                } else {
                    continue;
                }

                int hx = sx(hatch.x + 0.5D);
                int hy = sy(hatch.z + 0.5D);
                if (cx < mapX || cx >= mapX + mapW || cy < mapY || cy >= mapY + mapH) continue;
                if (hx < mapX || hx >= mapX + mapW || hy < mapY || hy >= mapY + mapH) continue;
                tess.setColorRGBA_I(rgb, alpha);
                tess.addVertex(cx, cy, 0.0D);
                tess.addVertex(hx, hy, 0.0D);
            }
        }
        tess.draw();

        // 舱室点（画在线之后，避免被线盖住）
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (!rec.multi) continue;
            for (int h = 0; h < rec.hatches.length; h++) {
                HatchRef hatch = rec.hatches[h];
                int rgb;
                if (power && (hatch.kind == HatchRef.ENERGY_IN || hatch.kind == HatchRef.ENERGY_OUT)) {
                    rgb = HatchRef.tierColor(hatch.tier);
                } else if (item && (hatch.kind == HatchRef.ITEM_IN || hatch.kind == HatchRef.ITEM_OUT)) {
                    rgb = HatchRef.colorOf(hatch.kind);
                } else if (fluid && (hatch.kind == HatchRef.FLUID_IN || hatch.kind == HatchRef.FLUID_OUT)) {
                    rgb = HatchRef.colorOf(hatch.kind);
                } else {
                    continue;
                }
                int hx = sx(hatch.x + 0.5D);
                int hy = sy(hatch.z + 0.5D);
                if (hx < mapX || hx >= mapX + mapW || hy < mapY || hy >= mapY + mapH) continue;
                drawRect(hx - 1, hy - 1, hx + 2, hy + 2, 0xFF000000 | rgb);
            }
        }

        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void drawSelection() {
        MachineRecord rec = selected != null ? selected : RadarState.tracked();
        if (rec == null || rec.dim != mc.thePlayer.dimension) return;

        int left = Math.max(mapX, Math.min(sx(rec.minX), sx(rec.maxX + 1)) - 2);
        int right = Math.min(mapX + mapW, Math.max(sx(rec.minX), sx(rec.maxX + 1)) + 2);
        int top = Math.max(mapY, Math.min(sy(rec.minZ), sy(rec.maxZ + 1)) - 2);
        int bottom = Math.min(mapY + mapH, Math.max(sy(rec.minZ), sy(rec.maxZ + 1)) + 2);
        drawRect(left, top, right, top + 1, 0xFFFFFFFF);
        drawRect(left, bottom - 1, right, bottom, 0xFFFFFFFF);
        drawRect(left, top, left + 1, bottom, 0xFFFFFFFF);
        drawRect(right - 1, top, right, bottom, 0xFFFFFFFF);
    }

    private void drawPlayer() {
        int x = sx(mc.thePlayer.posX);
        int y = sy(mc.thePlayer.posZ);
        drawRect(x - 2, y - 2, x + 3, y + 3, 0xFFFFFFFF);
        drawRect(x - 1, y - 1, x + 2, y + 2, 0xFF66FF66);
    }

    private void drawLegend() {
        int x = mapX + 4;
        int y = mapY + 4;
        x = legendItem(x, y, 0xFF4FC3F7, "多方块足迹");
        x = legendItem(x, y, 0xFF9E9E9E, "单方块");
        x = legendItem(x, y, 0xFFFFF176, "电网");
        x = legendItem(x, y, 0xFF4FC3F7, "物流");
        legendItem(x, y, 0xFF4DD0E1, "流体");
    }

    private int legendItem(int x, int y, int color, String label) {
        drawRect(x, y + 3, x + 5, y + 8, color);
        fontRendererObj.drawStringWithShadow(label, x + 7, y, 0x90A4AE);
        return x + 7 + fontRendererObj.getStringWidth(label) + 10;
    }

    private void drawTooltip(int mouseX, int mouseY) {
        MachineRecord rec = hover;
        if (rec == null) return;

        MachineGroup group = MachineGroups.groupOf(rec);
        List<String> lines = new ArrayList<String>(4);
        lines.add(rec.name + (group != null ? "  [" + group.name + "]" : ""));
        lines.add(
            "Y=" + rec.y + "  XZ " + rec.x + "," + rec.z + "  " + statusText(rec) + (rec.progress >= 0
                ? "  " + (rec.progress / 10) + "%"
                : ""));
        int stacked = countStacked(rec);
        if (stacked > 1) lines.add("同位置堆叠 " + stacked + " 台（按 Y 低→高绘制）");
        if (rec.multi) {
            lines.add(
                rec.blockCount + " 块 · 舱室 " + rec.hatches.length + (rec.hatches.length > 0 ? "（" + hatchSummary(rec) + "）" : ""));
        }

        int w = 0;
        for (int i = 0; i < lines.size(); i++) w = Math.max(w, fontRendererObj.getStringWidth(lines.get(i)));
        int boxW = w + 10;
        int boxH = lines.size() * 10 + 6;
        int bx = mouseX + 10;
        int by = mouseY + 8;
        if (bx + boxW > mapX + mapW) bx = mouseX - boxW - 8;
        if (by + boxH > mapY + mapH) by = mouseY - boxH - 8;

        drawRect(bx - 1, by - 1, bx + boxW + 1, by + boxH + 1, 0xD0000000);
        drawRect(bx, by, bx + boxW, by + boxH, 0xF0101418);
        for (int i = 0; i < lines.size(); i++) {
            fontRendererObj.drawStringWithShadow(
                lines.get(i),
                bx + 5,
                by + 4 + i * 10,
                i == 0 ? HatchRef.tierColor(rec.tier) : 0xB0BEC5);
        }
    }

    private int countStacked(MachineRecord rec) {
        int count = 0;
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord other = visible.get(i);
            if (other.x == rec.x && other.z == rec.z) count++;
        }
        return count;
    }

    private void drawOverlayInfo() {
        MachineRecord rec = hover != null ? hover : selected;
        int tx = mapX + 4;
        int ty = mapY + mapH - 11;

        if (rec == null) {
            drawString(fontRendererObj, "滚轮缩放 · 悬停查看 · 单击选中 · 双击追踪 · H 心电图", tx, ty, 0x78909C);
            return;
        }

        MachineGroup g = MachineGroups.groupOf(rec);
        String info = rec.name
            + (g != null ? " · " + g.name : "")
            + " · Y="
            + rec.y
            + " · "
            + statusText(rec)
            + (rec.multi ? " · " + rec.blockCount + "块" : "");
        drawString(fontRendererObj, RadarText.trim(fontRendererObj, info, mapW - 8), tx, ty, HatchRef.tierColor(rec.tier));
    }

    private String hatchSummary(MachineRecord rec) {
        int itemIn = rec.hatchCount(HatchRef.ITEM_IN);
        int itemOut = rec.hatchCount(HatchRef.ITEM_OUT);
        int fluidIn = rec.hatchCount(HatchRef.FLUID_IN);
        int fluidOut = rec.hatchCount(HatchRef.FLUID_OUT);
        int eu = rec.hatchCount(HatchRef.ENERGY_IN);
        return "电" + eu + " 物" + (itemIn + itemOut) + " 流" + (fluidIn + fluidOut);
    }

    // ------------------------------------------------------------------ 网格 / 坐标

    private void drawGroupAreas() {
        int mode = RadarConfig.groupPreviewMode;
        if (mode <= 0 || MachineGroups.GROUPS.isEmpty()) return;
        int dim = mc.thePlayer.dimension;

        for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
            MachineGroup group = MachineGroups.GROUPS.get(i);
            int[] b = MachineGroups.bounds(group, dim);
            if (b == null) continue;
            if (mode == 2) {
                b = new int[] { (b[0] >> 4) << 4, (b[1] >> 4) << 4, (b[2] >> 4) << 4,
                    (((b[3] + 16) >> 4) << 4) - 1, (((b[4] + 16) >> 4) << 4) - 1, (((b[5] + 16) >> 4) << 4) - 1 };
            }

            int left = Math.max(mapX, Math.min(sx(b[0]), sx(b[3] + 1)));
            int right = Math.min(mapX + mapW, Math.max(sx(b[0]), sx(b[3] + 1)));
            int top = Math.max(mapY, Math.min(sy(b[2]), sy(b[5] + 1)));
            int bottom = Math.min(mapY + mapH, Math.max(sy(b[2]), sy(b[5] + 1)));
            if (right <= left || bottom <= top) continue;

            drawRect(left, top, right, top + 1, 0xFF000000 | group.color);
            drawRect(left, bottom - 1, right, bottom, 0xFF000000 | group.color);
            drawRect(left, top, left + 1, bottom, 0xFF000000 | group.color);
            drawRect(right - 1, top, right, bottom, 0xFF000000 | group.color);
            drawString(fontRendererObj, group.name, left + 3, top + 2, group.color);
        }
    }

    private int sx(double worldX) {
        return (int) Math.round(mapX + mapW / 2.0D + (worldX - centerX) * scale);
    }

    private int sy(double worldZ) {
        return (int) Math.round(mapY + mapH / 2.0D + (worldZ - centerZ) * scale);
    }

    private double worldX(int px) {
        return centerX + (px - (mapX + mapW / 2.0D)) / scale;
    }

    private double worldZ(int py) {
        return centerZ + (py - (mapY + mapH / 2.0D)) / scale;
    }

    private void drawGridAndAxes() {
        int blockStep = 16;
        while (blockStep * scale < 12.0D) blockStep *= 2;

        double left = worldX(mapX);
        double right = worldX(mapX + mapW);
        double top = worldZ(mapY);
        double bottom = worldZ(mapY + mapH);

        int gx0 = (int) Math.floor(left / blockStep) * blockStep;
        for (int gx = gx0; gx <= right; gx += blockStep) {
            int x = sx(gx);
            drawRect(x, mapY, x + 1, mapY + mapH, gx == 0 ? 0x40FFFFFF : 0x18FFFFFF);
        }
        int gz0 = (int) Math.floor(top / blockStep) * blockStep;
        for (int gz = gz0; gz <= bottom; gz += blockStep) {
            int y = sy(gz);
            drawRect(mapX, y, mapX + mapW, y + 1, gz == 0 ? 0x40FFFFFF : 0x18FFFFFF);
        }

        int midX = mapX + mapW / 2;
        int midY = mapY + mapH / 2;
        drawString(fontRendererObj, "N", midX - 2, mapY + 3, 0x90A4AE);
        drawString(fontRendererObj, "S", midX - 2, mapY + mapH - 12, 0x90A4AE);
        drawString(fontRendererObj, "W", mapX + 3, midY - 4, 0x90A4AE);
        drawString(fontRendererObj, "E", mapX + mapW - 10, midY - 4, 0x90A4AE);

        String scaleText = String.format(Locale.ROOT, "%.1f px/格", scale);
        drawString(
            fontRendererObj,
            scaleText,
            mapX + mapW - fontRendererObj.getStringWidth(scaleText) - 4,
            mapY + mapH - 11,
            0x546E7A);
    }

    // ------------------------------------------------------------------ 交互

    private MachineRecord pick(int mouseX, int mouseY) {
        MachineRecord best = null;
        int bestY = Integer.MIN_VALUE;
        double bestSq = HIT_RADIUS * HIT_RADIUS;

        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (!shows(rec)) continue;
            if (rec.multi) {
                int left = Math.min(sx(rec.minX), sx(rec.maxX + 1));
                int right = Math.max(sx(rec.minX), sx(rec.maxX + 1));
                int top = Math.min(sy(rec.minZ), sy(rec.maxZ + 1));
                int bottom = Math.max(sy(rec.minZ), sy(rec.maxZ + 1));
                if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                    // 足迹重叠时取 Y 最高的那一台（和绘制顺序一致）
                    if (rec.y >= bestY) {
                        bestY = rec.y;
                        best = rec;
                    }
                }
            } else {
                double dx = mouseX - sx(rec.x + 0.5D);
                double dy = mouseY - sy(rec.z + 0.5D);
                double dSq = dx * dx + dy * dy;
                if (dSq <= bestSq) {
                    bestSq = dSq;
                    best = rec;
                }
            }
        }
        return best;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        double factor = wheel > 0 ? 1.15D : 1.0D / 1.15D;
        scale = Math.max(0.4D, Math.min(6.0D, scale * factor));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;

        MachineRecord hit = pick(mouseX, mouseY);
        if (hit == null) return;

        long now = System.currentTimeMillis();
        boolean doubleClick = hit == selected && now - clickAt < 350L && Math.abs(mouseX - clickX) < 8
            && Math.abs(mouseY - clickY) < 8;
        selected = hit;
        clickX = mouseX;
        clickY = mouseY;
        clickAt = now;
        if (doubleClick) {
            RadarState.track(hit, 15_000L);
            mc.displayGuiScreen(null);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_H) {
            openEcg();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 900:
                if (selected != null) {
                    RadarState.track(selected, 15_000L);
                    mc.displayGuiScreen(null);
                }
                return;
            case 901:
                mc.displayGuiScreen(null);
                return;
            case 902:
                mc.displayGuiScreen(new GuiMachineList());
                return;
            case 903:
                openEcg();
                return;
            case 910:
            case 911:
            case 912:
            case 913:
            case 914:
                toggleLayer(button);
                return;
            case 915:
                yFilter = (yFilter + 1) % 3;
                button.displayString = yFilterLabel();
                return;
            default:
                break;
        }
    }

    private void toggleLayer(GuiButton button) {
        switch (button.id) {
            case 910:
                layerMask ^= LAYER_MB;
                button.displayString = layerLabel(LAYER_MB, "多方块");
                break;
            case 911:
                layerMask ^= LAYER_SB;
                button.displayString = layerLabel(LAYER_SB, "单方块");
                break;
            case 912:
                layerMask ^= LAYER_POWER;
                button.displayString = layerLabel(LAYER_POWER, "电网");
                break;
            case 913:
                layerMask ^= LAYER_ITEM;
                button.displayString = layerLabel(LAYER_ITEM, "物流");
                break;
            case 914:
                layerMask ^= LAYER_FLUID;
                button.displayString = layerLabel(LAYER_FLUID, "流体");
                break;
            default:
                break;
        }
    }

    private void openEcg() {
        MachineRecord target = selected != null ? selected : hover;
        if (target == null) target = RadarState.tracked();
        if (target == null) return;
        mc.displayGuiScreen(new GuiEfficiency(target));
    }

    private static String statusText(MachineRecord rec) {
        return rec.statusText();
    }

    private double distSq(MachineRecord rec) {
        double dx = rec.x + 0.5D - centerX;
        double dz = rec.z + 0.5D - centerZ;
        return dx * dx + dz * dz;
    }
}
