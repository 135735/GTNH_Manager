package moe.takochan.machineradar.client;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

/**
 * 效率曲线绘制（列表的「分析」面板用；独立的心电图界面也走同一套几何）。
 *
 * <p>蓝柱 = 每点的合计耗电（绝对值归一化），绿线 = 每点的运行比例 0~1。
 */
public final class EcgPlot {

    private EcgPlot() {}

    /** 背景 + 网格 + 时间坐标（2 分钟窗口） */
    public static void frame(FontRenderer fr, int x, int y, int w, int h) {
        rect(x, y, x + w, y + h, 0xFF0A0D10);
        for (int i = 1; i < 4; i++) {
            int yy = y + h * i / 4;
            if (yy > y && yy < y + h) rect(x, yy, x + w, yy + 1, 0x18FFFFFF);
        }
        for (int i = 1; i < 6; i++) {
            int xx = x + w * i / 6;
            if (xx > x && xx < x + w) rect(xx, y, xx + 1, y + h, 0x12FFFFFF);
        }
        fr.drawStringWithShadow("100%", x + 3, y + 2, 0x546E7A);
        fr.drawStringWithShadow("2分钟前", x + 3, y + h - 10, 0x546E7A);
        String now = "现在";
        fr.drawStringWithShadow(now, x + w - fr.getStringWidth(now) - 3, y + h - 10, 0x546E7A);
    }

    public static void trace(FontRenderer fr, int x, int y, int w, int h, MachineHistory.GroupTrace t) {
        frame(fr, x, y, w, h);
        if (t == null || !t.valid() || t.points < 2) {
            empty(fr, x, y, w, h, "还没有历史数据：保持雷达开启几秒");
            return;
        }

        int n = t.points;
        int baseY = y + h - 1;
        int plotH = Math.max(4, h - 8);

        long maxEu = 1L;
        for (int i = 0; i < n; i++) maxEu = Math.max(maxEu, Math.abs(t.eut[i]));
        int barW = Math.max(1, w / n);
        for (int i = 0; i < n; i++) {
            long eu = Math.abs(t.eut[i]);
            int bh = (int) (eu * plotH / maxEu);
            if (bh <= 0) continue;
            int bx = x + i * w / n;
            rect(bx, baseY - bh, bx + barW, baseY, 0x303FB0FF);
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.0F);
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINE_STRIP);
        tess.setColorRGBA_I(0x66FF66, 235);
        for (int i = 0; i < n; i++) {
            double ratio = t.activity[i];
            double px = x + (n == 1 ? 0 : i * (w - 1.0D) / (n - 1.0D));
            double py = baseY - ratio * plotH;
            tess.addVertex(px, py, 0.0D);
        }
        tess.draw();
        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        int running = (int) Math.round(t.activity[n - 1] * t.members);
        String label = "● " + running + "/" + t.members + " 台运行";
        fr.drawStringWithShadow(
            label,
            x + w - fr.getStringWidth(label) - 3,
            y + 3,
            running > 0 ? 0x66FF66 : 0x90A4AE);
    }

    public static void empty(FontRenderer fr, int x, int y, int w, int h, String msg) {
        fr.drawStringWithShadow(msg, x + (w - fr.getStringWidth(msg)) / 2, y + h / 2 - 4, 0x78909C);
    }

    private static void rect(int x0, int y0, int x1, int y1, int color) {
        float a = (color >>> 24) / 255.0F;
        float r = (color >> 16 & 255) / 255.0F;
        float g = (color >> 8 & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        Tessellator t = Tessellator.instance;
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        t.startDrawingQuads();
        t.setColorRGBA_F(r, g, b, a);
        t.addVertex(x0, y1, 0.0D);
        t.addVertex(x1, y1, 0.0D);
        t.addVertex(x1, y0, 0.0D);
        t.addVertex(x0, y0, 0.0D);
        t.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
