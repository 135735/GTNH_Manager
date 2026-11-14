package moe.takochan.machineradar.client.gui;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import moe.takochan.machineradar.client.MachineGroups;
import moe.takochan.machineradar.client.MachineHistory;
import moe.takochan.machineradar.client.ProductionLedger;
import moe.takochan.machineradar.client.RadarText;
import moe.takochan.machineradar.radar.HatchRef;
import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 效率心电图：把 {@link MachineHistory} 的最近 2 分钟采样画成滚动曲线。
 *
 * <p>两种视图，右上角按钮或列表入口决定：
 * <ul>
 * <li>单机：绿线 = 是否在跑配方的心跳，背后柱子 = 当时的耗电；</li>
 * <li>分组：绿线 = 当前时刻「在跑配方的机器占比」（0~1），柱子 = 该组在那一刻的合计耗电。
 * 分组视图按成员采样末端对齐后逐点求和，见 {@link MachineHistory#trace}。</li>
 * </ul>
 * 界面每帧重读历史缓冲，所以开着界面时曲线是实时滚动的。
 */
public class GuiEfficiency extends GuiScreen {

    private static final int PAD = 12;
    private static final int HEADER_H = 58;
    private static final int FOOTER_H = 34;

    private MachineRecord target;
    private MachineGroup group;
    /** 切视图时不能直接 initGui（actionPerformed 里改 buttonList 会 CME），延到下一帧 */
    private boolean layoutDirty;

    private int panelX, panelY, panelW, panelH;
    private int graphX, graphY, graphW, graphH;
    /** 分组视图右侧的资源台账列表 */
    private int resX, resY, resW, resH;
    private static final int RES_W = 236;
    private static final int RES_ROW_H = 13;
    /** 当前选中的资源 key；null = 看整组活动曲线 */
    private String selFlowKey;
    private int flowWindow = ProductionLedger.WINDOW_SEC;

    public GuiEfficiency(MachineRecord target) {
        this.target = target;
    }

    public GuiEfficiency(MachineGroup group) {
        this.group = group;
    }

    private boolean groupView() {
        return group != null;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        MachineGroups.ensureLoaded();

        panelW = Math.min(width - 40, groupView() ? 780 : 640);
        panelH = Math.min(height - 40, 400);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        graphX = panelX + PAD;
        graphY = panelY + HEADER_H;
        int resSpace = groupView() ? RES_W + 8 : 0;
        graphW = panelW - PAD * 2 - resSpace;
        graphH = panelH - HEADER_H - FOOTER_H;

        resX = graphX + graphW + 8;
        resY = graphY;
        resW = RES_W;
        resH = graphH;

        int by = panelY + panelH - FOOTER_H + 7;
        GuiButton track = new GuiButton(900, panelX + PAD, by, 80, 20, "追踪");
        track.enabled = target != null && !groupView();
        buttonList.add(track);
        int bx = panelX + PAD + 86;
        buttonList.add(new GuiButton(902, bx, by, 80, 20, "回列表"));
        bx += 86;
        if (!groupView() && target != null && MachineGroups.groupOf(target) != null) {
            buttonList.add(new GuiButton(903, bx, by, 80, 20, "看分组"));
        } else if (groupView()) {
            buttonList.add(new GuiButton(904, bx, by, 80, 20, "看单机"));
            bx += 86;
            buttonList.add(new GuiButton(905, bx, by, 96, 20, windowLabel()));
        }
        buttonList.add(new GuiButton(901, panelX + panelW - PAD - 60, by, 60, 20, "关闭"));
    }

    private String windowLabel() {
        return "窗口：" + ProductionLedger.windowName(flowWindow);
    }

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
        // 快照换了一轮就重新找一下目标（机器对象每次扫描都是新的）
        resolveTarget();

        drawDefaultBackground();

        drawRect(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xC0000000);
        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xE8101418);
        drawRect(panelX, panelY, panelX + panelW, panelY + 1, 0xFF3A6EA5);

        drawCenteredString(
            fontRendererObj,
            groupView() ? "效率心电图 · 分组（最近 2 分钟）" : "效率心电图 · 最近 2 分钟",
            width / 2,
            panelY + 8,
            0xFFFFFF);

        if (groupView()) drawGroupHeader();
        else drawMachineHeader();

        drawGraph();
        if (groupView()) drawResourceList(mouseX, mouseY);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 机器对象来自快照，换了一轮扫描就按坐标重新定位，避免界面里拿着旧对象 */
    private void resolveTarget() {
        if (target == null) return;
        java.util.List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim == target.dim && rec.x == target.x && rec.y == target.y && rec.z == target.z) {
                target = rec;
                return;
            }
        }
    }

    private void drawMachineHeader() {
        if (target == null) {
            drawString(fontRendererObj, "没有选中的机器", panelX + PAD, panelY + 24, 0x90A4AE);
            return;
        }
        MachineGroup g = MachineGroups.groupOf(target);
        String line = target.name
            + (g != null ? "  [" + g.name + "]" : "")
            + "  ·  "
            + target.statusText()
            + "  ·  Y="
            + target.y;
        drawString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, line, panelW - PAD * 2),
            panelX + PAD,
            panelY + 22,
            target.colorOf());

        MachineHistory.Samples samples = MachineHistory.get(target);
        int points = samples == null ? 0 : samples.size();
        double run = MachineHistory.runningFraction(target);
        int avgEu = MachineHistory.avgEut(target);
        double out = MachineHistory.avgOutputPerSecond(target);
        String stats = "运行 "
            + (run < 0 ? "?" : Math.round(run * 100) + "%")
            + "  ·  平均 "
            + RadarText.fmt(Math.abs(avgEu))
            + " EU/t"
            + (out > 0 ? String.format(Locale.ROOT, "  ·  产出 %.2f/s", out) : "")
            + "  ·  "
            + points
            + " 个采样";
        drawString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, stats, panelW - PAD * 2),
            panelX + PAD,
            panelY + 33,
            0x90A4AE);

        String tier = target.tier >= 0 ? HatchRef.tierName(target.tier) : "?";
        drawString(
            fontRendererObj,
            RadarText.trim(
                fontRendererObj,
                tier + "  ·  " + (target.multi ? (target.assembled ? "已成型" : "未成型") : "单方块") + "  ·  "
                    + target.x + ", " + target.y + ", " + target.z,
                panelW - PAD * 2),
            panelX + PAD,
            panelY + 44,
            0x607D8B);
    }

    private void drawGroupHeader() {
        int dim = mc.thePlayer.dimension;
        MachineHistory.GroupTrace trace = MachineHistory.trace(group, dim);
        int online = MachineGroups.onlineCount(group, dim);

        StringBuilder line = new StringBuilder(group.name);
        line.append("  ·  ")
            .append(online)
            .append('/')
            .append(group.size())
            .append(" 台在线");
        if (group.hasRegion(dim)) line.append("  ·  区域 ")
            .append(group.regionSizeText());
        drawString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, line.toString(), panelW - PAD * 2),
            panelX + PAD,
            panelY + 22,
            group.color);

        String stats;
        if (!trace.valid()) {
            stats = "还没有历史数据：保持雷达开启几秒后再看";
        } else {
            stats = "运行 "
                + (trace.runningFraction < 0 ? "?" : Math.round(trace.runningFraction * 100) + "%")
                + "  ·  合计 "
                + RadarText.fmt(Math.abs(trace.avgEut))
                + " EU/t"
                + (trace.outputPerSecond > 0 ? String.format(Locale.ROOT, "  ·  产出 %.2f/s", trace.outputPerSecond)
                    : "")
                + "  ·  "
                + trace.members
                + " 台有采样 / "
                + trace.points
                + " 点";
        }
        drawString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, stats, panelW - PAD * 2),
            panelX + PAD,
            panelY + 33,
            0x90A4AE);

        ProductionLedger.GroupLedger ledger = ProductionLedger.ledger(group);
        String hint;
        if (ledger != null && !ledger.flows.isEmpty()) {
            hint = "累计配方 " + (int) ledger.completionsTotal + " · 窗口 "
                + RadarText.fmt(ledger.avgEut(flowWindow)) + " EU/t · 资源 " + ledger.flows.size() + " 种"
                + "（右侧点资源看曲线）";
        } else {
            hint = "绿线 = 在跑配方的机器占比  ·  蓝柱 = 该组合计耗电（绝对值）";
        }
        drawString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, hint, panelW - PAD * 2),
            panelX + PAD,
            panelY + 44,
            0x607D8B);
    }

    private void drawGraph() {
        if (groupView() && selFlowKey != null) {
            drawFlowChart();
            return;
        }
        drawRect(graphX, graphY, graphX + graphW, graphY + graphH, 0xFF0A0D10);

        // 网格
        for (int i = 1; i < 4; i++) {
            int y = graphY + graphH * i / 4;
            drawRect(graphX, y, graphX + graphW, y + 1, 0x18FFFFFF);
        }
        for (int i = 1; i < 6; i++) {
            int x = graphX + graphW * i / 6;
            drawRect(x, graphY, x + 1, graphY + graphH, 0x12FFFFFF);
        }
        drawString(fontRendererObj, "100%", graphX + 3, graphY + 2, 0x546E7A);
        drawString(fontRendererObj, "2分钟前", graphX + 3, graphY + graphH - 10, 0x546E7A);
        String now = "现在";
        drawString(
            fontRendererObj,
            now,
            graphX + graphW - fontRendererObj.getStringWidth(now) - 3,
            graphY + graphH - 10,
            0x546E7A);

        if (groupView()) drawGroupSeries();
        else drawMachineSeries();
    }

    private void drawMachineSeries() {
        MachineHistory.Samples samples = target == null ? null : MachineHistory.get(target);
        if (samples == null || samples.size() < 2) {
            drawCenteredString(
                fontRendererObj,
                "还没有历史数据：保持雷达开启几秒后再看",
                graphX + graphW / 2,
                graphY + graphH / 2 - 4,
                0x78909C);
            return;
        }

        int n = samples.size();
        int baseY = graphY + graphH - 1;

        long maxEu = 1L;
        for (int i = 0; i < n; i++) maxEu = Math.max(maxEu, Math.abs((long) samples.eut(i)));
        for (int i = 0; i < n; i++) {
            long eu = Math.abs((long) samples.eut(i));
            int h = (int) (Math.max(0L, eu) * (graphH - 8) / maxEu);
            if (h <= 0) continue;
            int x = graphX + i * graphW / n;
            int w = Math.max(1, graphW / n);
            drawRect(x, baseY - h, x + w, baseY, 0x303FB0FF);
        }

        // 心跳线：运行 = 1，停机 = 0.05
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(2.0F);
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA_I(0x66FF66, 235);
        for (int i = 0; i < n; i++) {
            double activity = (samples.flags(i) & 1) != 0 ? 1.0D : 0.05D;
            double x = graphX + (n == 1 ? 0 : i * (graphW - 1.0D) / (n - 1.0D));
            double y = baseY - activity * (graphH - 8);
            tess.addVertex(x, y, 0.0D);
        }
        tess.draw();

        int current = (samples.flags(n - 1) & 1) != 0 ? 1 : 0;
        drawString(
            fontRendererObj,
            current == 1 ? "● 运行中" : "○ 停机",
            graphX + graphW - 44,
            graphY + 3,
            current == 1 ? 0x66FF66 : 0x90A4AE);

        boolean hasProgress = false;
        for (int i = 0; i < n; i++) {
            if (samples.progress(i) >= 0) {
                hasProgress = true;
                break;
            }
        }
        if (hasProgress) {
            tess.startDrawing(GL11.GL_LINES);
            tess.setColorRGBA_I(0xFFB74D, 130);
            for (int i = 1; i < n; i++) {
                int p0 = samples.progress(i - 1);
                int p1 = samples.progress(i);
                if (p0 < 0 || p1 < 0) continue;
                double x0 = graphX + (i - 1) * (graphW - 1.0D) / (n - 1.0D);
                double x1 = graphX + i * (graphW - 1.0D) / (n - 1.0D);
                tess.addVertex(x0, baseY - (p0 / 1000.0D) * (graphH - 8), 0.0D);
                tess.addVertex(x1, baseY - (p1 / 1000.0D) * (graphH - 8), 0.0D);
            }
            tess.draw();
        }
        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void drawGroupSeries() {
        MachineHistory.GroupTrace trace = MachineHistory.trace(group, mc.thePlayer.dimension);
        if (!trace.valid() || trace.points < 2) {
            drawCenteredString(
                fontRendererObj,
                "分组还没有历史数据：保持雷达开启几秒后再看",
                graphX + graphW / 2,
                graphY + graphH / 2 - 4,
                0x78909C);
            return;
        }

        int n = trace.points;
        int baseY = graphY + graphH - 1;

        long maxEu = 1L;
        for (int i = 0; i < n; i++) maxEu = Math.max(maxEu, Math.abs(trace.eut[i]));
        for (int i = 0; i < n; i++) {
            long eu = Math.abs(trace.eut[i]);
            int h = (int) (eu * (graphH - 8) / maxEu);
            if (h <= 0) continue;
            int x = graphX + i * graphW / n;
            int w = Math.max(1, graphW / n);
            drawRect(x, baseY - h, x + w, baseY, 0x303FB0FF);
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glLineWidth(2.0F);
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA_I(0x66FF66, 235);
        for (int i = 0; i < n; i++) {
            double ratio = trace.activity[i];
            double x = graphX + (n == 1 ? 0 : i * (graphW - 1.0D) / (n - 1.0D));
            double y = baseY - ratio * (graphH - 8);
            tess.addVertex(x, y, 0.0D);
        }
        tess.draw();
        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        int running = (int) Math.round(trace.activity[n - 1] * trace.members);
        String label = "● " + running + "/" + trace.members + " 台运行";
        drawString(
            fontRendererObj,
            label,
            graphX + graphW - fontRendererObj.getStringWidth(label) - 3,
            graphY + 3,
            running > 0 ? 0x66FF66 : 0x90A4AE);
    }

    // ------------------------------------------------------------------ 分组资源台账

    private ProductionLedger.Flow findFlow() {
        if (selFlowKey == null) return null;
        List<ProductionLedger.Flow> flows = ProductionLedger.flows(group, flowWindow);
        for (int i = 0; i < flows.size(); i++) {
            if (flows.get(i).key.equals(selFlowKey)) return flows.get(i);
        }
        return null;
    }

    private void drawFlowChart() {
        ProductionLedger.Flow f = findFlow();
        if (f == null) {
            selFlowKey = null;
            drawGraph();
            return;
        }
        long[] prod = ProductionLedger.series(f, flowWindow, true);
        long[] cons = ProductionLedger.series(f, flowWindow, false);
        int n = prod.length;

        drawRect(graphX, graphY, graphX + graphW, graphY + graphH, 0xFF0A0D10);
        for (int i = 1; i < 4; i++) {
            int y = graphY + graphH * i / 4;
            drawRect(graphX, y, graphX + graphW, y + 1, 0x18FFFFFF);
        }

        long max = 1L;
        for (int i = 0; i < n; i++) {
            if (prod[i] > max) max = prod[i];
            if (cons[i] > max) max = cons[i];
        }
        if (max <= 1L) {
            drawCenteredString(
                fontRendererObj,
                "窗口内没有该资源的流量",
                graphX + graphW / 2,
                graphY + graphH / 2 - 4,
                0x78909C);
            return;
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.0F);
        Tessellator tess = Tessellator.instance;
        int baseY = graphY + graphH - 1;
        int plotH = graphH - 8;
        flowLine(tess, prod, n, max, baseY, plotH, 0x66FF66);
        flowLine(tess, cons, n, max, baseY, plotH, 0xFF8A80);
        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        drawString(
            fontRendererObj,
            "● 产出 " + RadarText.fmt(ProductionLedger.produced(f, flowWindow)),
            graphX + 3,
            graphY + 3,
            0x66FF66);
        drawString(
            fontRendererObj,
            "● 消耗 " + RadarText.fmt(ProductionLedger.consumed(f, flowWindow)),
            graphX + 3,
            graphY + 13,
            0xFF8A80);
        String cap = ProductionLedger.windowName(flowWindow) + " · " + f.name;
        drawString(
            fontRendererObj,
            cap,
            graphX + graphW - fontRendererObj.getStringWidth(cap) - 3,
            graphY + graphH - 10,
            0x546E7A);
    }

    private void flowLine(Tessellator tess, long[] data, int n, long max, int baseY, int plotH, int color) {
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA_I(color, 235);
        for (int i = 0; i < n; i++) {
            double x = graphX + (n == 1 ? 0 : i * (graphW - 1.0D) / (n - 1.0D));
            double y = baseY - (double) data[i] * plotH / max;
            tess.addVertex(x, y, 0.0D);
        }
        tess.draw();
    }

    private void drawResourceList(int mouseX, int mouseY) {
        if (resW <= 0) return;
        drawRect(resX - 4, resY - 4, resX + resW + 4, resY + resH + 4, 0x70000000);
        drawString(fontRendererObj, "资源 · " + ProductionLedger.windowName(flowWindow), resX, resY, 0xCFD8DC);
        int y = resY + 12;
        drawResourceRow(null, y, mouseX, mouseY);
        y += RES_ROW_H;

        List<ProductionLedger.Flow> flows = ProductionLedger.flows(group, flowWindow);
        if (flows.isEmpty()) {
            drawString(fontRendererObj, "还没有配方数据", resX, y + 2, 0x78909C);
            return;
        }
        int maxRows = (resY + resH - y) / RES_ROW_H;
        for (int i = 0; i < flows.size() && i < maxRows; i++) {
            drawResourceRow(flows.get(i), y, mouseX, mouseY);
            y += RES_ROW_H;
        }
        if (flows.size() > maxRows) {
            drawString(fontRendererObj, "+" + (flows.size() - maxRows) + " 更多", resX, y, 0x546E7A);
        }
    }

    private void drawResourceRow(ProductionLedger.Flow f, int y, int mouseX, int mouseY) {
        boolean selected = f == null ? selFlowKey == null : f.key.equals(selFlowKey);
        boolean hover = mouseX >= resX && mouseX < resX + resW && mouseY >= y && mouseY < y + RES_ROW_H - 1;
        if (selected) drawRect(resX, y, resX + resW, y + RES_ROW_H - 1, 0x503F6FA5);
        else if (hover) drawRect(resX, y, resX + resW, y + RES_ROW_H - 1, 0x20FFFFFF);

        if (f == null) {
            drawString(fontRendererObj, "整组活动曲线", resX + 2, y + 1, selected ? 0xFFFFFF : 0xCFD8DC);
            return;
        }
        long net = ProductionLedger.net(f, flowWindow);
        String right = (net >= 0 ? "+" : "") + RadarText.fmt(net);
        int rw = fontRendererObj.getStringWidth(right);
        drawString(fontRendererObj, right, resX + resW - 3 - rw, y + 1, net >= 0 ? 0x81C784 : 0xFF8A80);
        String name = RadarText.trim(fontRendererObj, (f.fluid ? "流体 " : "") + f.name, Math.max(20, resW - rw - 12));
        drawString(fontRendererObj, name, resX + 2, y + 1, selected ? 0xFFFFFF : 0xB0BEC5);
    }

    /** @return -1 = 整组活动行，-2 = 没点中，>=0 = 资源下标 */
    private int flowRowAt(int mouseX, int mouseY) {
        if (!groupView() || resW <= 0) return -2;
        if (mouseX < resX || mouseX >= resX + resW || mouseY < resY + 12) return -2;
        int y = resY + 12;
        if (mouseY >= y && mouseY < y + RES_ROW_H) return -1;
        y += RES_ROW_H;
        List<ProductionLedger.Flow> flows = ProductionLedger.flows(group, flowWindow);
        int maxRows = (resY + resH - y) / RES_ROW_H;
        for (int i = 0; i < flows.size() && i < maxRows; i++) {
            if (mouseY >= y && mouseY < y + RES_ROW_H) return i;
            y += RES_ROW_H;
        }
        return -2;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        int row = flowRowAt(mouseX, mouseY);
        if (row == -1) {
            selFlowKey = null;
            return;
        }
        if (row < 0) return;
        List<ProductionLedger.Flow> flows = ProductionLedger.flows(group, flowWindow);
        if (row < flows.size()) selFlowKey = flows.get(row).key;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 900) {
            if (target != null && !groupView()) {
                RadarState.track(target, 15_000L);
                mc.displayGuiScreen(null);
            }
            return;
        }
        if (button.id == 901) {
            mc.displayGuiScreen(null);
            return;
        }
        if (button.id == 902) {
            mc.displayGuiScreen(new GuiMachineList());
            return;
        }
        if (button.id == 903) {
            MachineGroup g = MachineGroups.groupOf(target);
            if (g != null) {
                this.group = g;
                layoutDirty = true;
            }
            return;
        }
        if (button.id == 904) {
            if (group != null && !group.machines.isEmpty()) {
                java.util.List<MachineRecord> online = MachineGroups.onlineMembers(group);
                if (!online.isEmpty()) {
                    this.target = online.get(0);
                    this.group = null;
                    selFlowKey = null;
                    layoutDirty = true;
                }
            }
            return;
        }
        if (button.id == 905) {
            flowWindow = (flowWindow + 1) % 3;
            button.displayString = windowLabel();
        }
    }
}
