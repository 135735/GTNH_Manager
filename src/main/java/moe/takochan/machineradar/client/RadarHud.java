package moe.takochan.machineradar.client;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import moe.takochan.machineradar.radar.HatchRef;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;
import moe.takochan.machineradar.radar.RegionSelection;

public class RadarHud {

    private static final int WIDTH = 236;
    private static final int PAD = 6;
    private static final int ROW = 10;
    private static final int HROW = 12;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.hideGUI || mc.thePlayer == null) return;

        FontRenderer fr = mc.fontRenderer;
        int sw = event.resolution.getScaledWidth();
        int sh = event.resolution.getScaledHeight();

        // 选区提示和雷达开关无关：框选本身是独立工具
        drawSelectionHint(mc, fr, sw, sh);

        if (!RadarState.enabled) return;

        MachineRecord rec = RadarRenderer.FOCUS;

        drawTrackedBanner(mc, fr, sw);

        if (rec == null) return;

        // 舱室行数按屏幕高度来，不再写死 12 行
        int fitRows = Math.max(4, (sh - 150) / HROW);
        int rows = Math.min(rec.hatches.length, fitRows);
        int height = PAD * 2 + ROW * 4 + 6 + rows * HROW + (rec.hatches.length > rows ? ROW : 0);
        int x = sw - WIDTH - 8;
        int y = sh / 2 - height / 2;

        Gui.drawRect(x - 1, y - 1, x + WIDTH + 1, y + height + 1, 0xC0000000);
        Gui.drawRect(x, y, x + WIDTH, y + height, 0xA0101418);

        int tx = x + PAD;
        int ty = y + PAD;

        // 标题：名字 + 等级 + 距离
        String dist = ((int) Math.sqrt(
            (rec.x + 0.5 - mc.thePlayer.posX) * (rec.x + 0.5 - mc.thePlayer.posX)
                + (rec.y + 0.5 - mc.thePlayer.posY) * (rec.y + 0.5 - mc.thePlayer.posY)
                + (rec.z + 0.5 - mc.thePlayer.posZ) * (rec.z + 0.5 - mc.thePlayer.posZ))) + "m";
        String tier = rec.tier >= 0 ? HatchRef.tierName(rec.tier) : "";
        int nameW = WIDTH - PAD * 2 - 16 - fr.getStringWidth(tier) - fr.getStringWidth(dist);
        fr.drawStringWithShadow(trim(fr, rec.name, nameW), tx, ty, rec.colorOf());
        if (tier.length() > 0) {
            fr.drawStringWithShadow(
                tier,
                tx + fr.getStringWidth(trim(fr, rec.name, nameW)) + 4,
                ty,
                HatchRef.tierColor(rec.tier));
        }
        fr.drawStringWithShadow(dist, x + WIDTH - PAD - fr.getStringWidth(dist), ty, 0x9E9E9E);

        // 坐标 + 状态
        ty += ROW;
        fr.drawStringWithShadow(
            rec.x + ", " + rec.y + ", " + rec.z + "  " + rec.blockCount + "块",
            tx,
            ty,
            0x78909C);
        String status = statusText(rec);
        if (rec.maintenanceMissing > 0) status = "缺维护 " + rec.maintenanceMissing;
        fr.drawStringWithShadow(status, x + WIDTH - PAD - fr.getStringWidth(status), ty, rec.colorOf());

        // 进度条
        ty += ROW;
        int barW = WIDTH - PAD * 2;
        Gui.drawRect(tx, ty + 3, tx + barW, ty + 5, 0x40FFFFFF);
        if (rec.progress >= 0) {
            Gui.drawRect(tx, ty + 3, tx + barW * rec.progress / 1000, ty + 5, 0xFF66FF66);
        }
        ty += ROW;

        // 电力 / 配方 / 产出速率
        StringBuilder line = new StringBuilder();
        if (rec.eut != 0) line.append("耗电 ")
            .append(fmt(rec.eut))
            .append(" EU/t");
        if (rec.maxEuInput > 0) {
            if (line.length() > 0) line.append("  ·  ");
            line.append("上限 ")
                .append(fmt(rec.maxEuInput))
                .append("V/")
                .append(rec.maxAmps)
                .append("A");
        }
        if (rec.recipeSeconds() > 0) {
            if (line.length() > 0) line.append("  ·  ");
            line.append("单配方 ")
                .append(String.format(Locale.ROOT, "%.1fs", rec.recipeSeconds()));
        }
        if (rec.outputPerSecond() > 0) {
            if (line.length() > 0) line.append("  ·  ");
            line.append(String.format(Locale.ROOT, "%.2f/s", rec.outputPerSecond()));
        }
        if (line.length() == 0) line.append(rec.multi ? "无配方数据" : "空闲");
        fr.drawStringWithShadow(trim(fr, line.toString(), barW), tx, ty, 0xB0BEC5);
        ty += ROW;

        // 产出
        if (rec.outputName != null && !rec.outputName.isEmpty()) {
            int iconX = tx;
            if (rec.outputIcon != null) {
                drawIcon(mc, fr, rec.outputIcon, iconX, ty - 2);
                iconX += 18;
            }
            fr.drawStringWithShadow(
                trim(fr, "产出 " + rec.outputName + " ×" + rec.outputAmount, WIDTH - PAD * 2 - (iconX - tx)),
                iconX,
                ty,
                0xE0E0E0);
        }
        ty += ROW;

        Gui.drawRect(tx, ty, x + WIDTH - PAD, ty + 1, 0x30FFFFFF);
        ty += 4;

        if (rec.hatches.length == 0) {
            fr.drawStringWithShadow(rec.multi ? "无舱室" : "单方块机器", tx, ty, 0x757575);
            return;
        }

        for (int i = 0; i < rows; i++) {
            HatchRef h = rec.hatches[i];
            int ix = tx;

            if (h.icon != null) {
                drawIcon(mc, fr, h.icon, ix, ty);
                ix += 18;
            } else {
                Gui.drawRect(ix, ty + 1, ix + 10, ty + 11, 0xFF000000 | HatchRef.colorOf(h.kind));
                ix += 14;
            }

            String left = HatchRef.nameOf(h.kind);
            if (h.tier >= 0) left += " " + HatchRef.tierName(h.tier);
            fr.drawStringWithShadow(left, ix, ty, 0xE0E0E0);
            int leftW = fr.getStringWidth(left);

            String right = detail(h);
            int rw = fr.getStringWidth(right);
            int rx = x + WIDTH - PAD - rw;
            if (right.length() > 0 && ix + leftW + 4 < rx) {
                fr.drawStringWithShadow(right, rx, ty, 0xB0BEC5);
            }
            ty += HROW;
        }

        if (rec.hatches.length > rows) {
            fr.drawStringWithShadow("+" + (rec.hatches.length - rows) + " 个舱室（屏幕高度不够）", tx, ty, 0x757575);
        }
    }

    /** 3D 选区工具的状态条（底部中间）：设角点 / 已框完可归组 */
    private void drawSelectionHint(Minecraft mc, FontRenderer fr, int sw, int sh) {
        if (!RegionSelection.isActive()) return;

        String line1;
        String line2;
        int accent;
        if (!RegionSelection.hasA()) {
            line1 = "3D 选区：准星对着方块按 V 设角点1";
            line2 = "Ctrl+V 取消";
            accent = 0x4DD0E1;
        } else if (!RegionSelection.isComplete()) {
            line1 = "选区 · 角点1 = " + RegionSelection.ax() + ", " + RegionSelection.ay() + ", " + RegionSelection.az();
            line2 = "准星对着另一个角点按 V · Ctrl+V 取消";
            accent = 0x4DD0E1;
        } else {
            int n = RegionSelection.countMachines(mc.thePlayer.dimension);
            line1 = "选区 " + RegionSelection.sizeText() + " · 内含 " + n + " 台机器";
            line2 = "按 V 归组（新建 / 加入已有）· Ctrl+V 取消";
            accent = 0x81C784;
        }

        int w = Math.max(fr.getStringWidth(line1), fr.getStringWidth(line2)) + 16;
        int x = (sw - w) / 2;
        int y = sh - 78;
        Gui.drawRect(x, y, x + w, y + 26, 0xA0000000);
        Gui.drawRect(x, y, x + 2, y + 26, accent);
        fr.drawStringWithShadow(line1, x + 7, y + 4, 0xFFFFFF);
        fr.drawStringWithShadow(line2, x + 7, y + 14, 0x90A4AE);
    }

    private void drawTrackedBanner(Minecraft mc, FontRenderer fr, int sw) {        MachineRecord tracked = RadarState.tracked();
        if (tracked == null) return;

        double dx = tracked.x + 0.5D - mc.thePlayer.posX;
        double dy = tracked.y + 0.5D - mc.thePlayer.posY;
        double dz = tracked.z + 0.5D - mc.thePlayer.posZ;
        int dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
        long left = Math.max(0L, (RadarState.trackedUntil - System.currentTimeMillis()) / 1000L);

        String text = "◎ 追踪 " + tracked.name + " · " + dist + "m · " + left + "s";
        int w = fr.getStringWidth(text);
        int bx = (sw - w) / 2 - 6;
        Gui.drawRect(bx, 4, bx + w + 12, 20, 0xA0000000);
        Gui.drawRect(bx, 4, bx + 2, 20, 0xFFFFD54F);
        fr.drawStringWithShadow(text, bx + 6, 8, 0xFFF176);
    }

    private static void drawIcon(Minecraft mc, FontRenderer fr, ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(fr, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }

    private static String detail(HatchRef h) {
        String face = h.facing >= 0 && h.kind != HatchRef.MAINTENANCE ? HatchRef.facingName(h.facing) + " " : "";
        String body;
        switch (h.kind) {
            case HatchRef.ENERGY_IN:
            case HatchRef.ENERGY_OUT:
                body = (h.maxEu > 0 ? fmt(h.maxEu) + "V" : "") + (h.amps > 0 ? "/" + h.amps + "A" : "")
                    + (h.capacity > 0 ? "  " + eu(h.capacity) : "");
                break;
            case HatchRef.FLUID_IN:
            case HatchRef.FLUID_OUT:
                body = h.content.isEmpty() ? "空" : h.content + "  " + num(h.amount) + "/" + num(h.capacity);
                break;
            case HatchRef.MAINTENANCE:
                body = h.content;
                break;
            case HatchRef.ITEM_IN:
            case HatchRef.ITEM_OUT:
            case HatchRef.COKE:
            case HatchRef.ME:
                body = (h.content.isEmpty() ? "" : h.content + "  ")
                    + (h.amount > 0 ? num(h.amount) + "×  " : "")
                    + h.usedSlots
                    + "/"
                    + h.totalSlots;
                break;
            default:
                body = h.content;
                break;
        }
        return face + body;
    }

    private static String eu(long v) {
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM EU", v / 1_000_000.0D);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.0fk EU", v / 1_000.0D);
        return v + " EU";
    }

    private static String fmt(long v) {
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0D);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.1fk", v / 1_000.0D);
        return Long.toString(v);
    }

    private static String num(long v) {
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0D);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.1fk", v / 1_000.0D);
        return Long.toString(v);
    }

    private static String trim(FontRenderer fr, String s, int maxWidth) {
        if (fr.getStringWidth(s) <= maxWidth) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (fr.getStringWidth(sb.toString() + s.charAt(i) + "…") > maxWidth) break;
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    private static String statusText(MachineRecord rec) {
        return rec.statusText();
    }
}