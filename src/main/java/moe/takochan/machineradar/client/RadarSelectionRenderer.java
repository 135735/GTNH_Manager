package moe.takochan.machineradar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import moe.takochan.machineradar.radar.RegionSelection;

/**
 * 世界里的 3D 选区框：12 条边 + 8 个角点。
 *
 * <p>刻意关掉深度测试（穿墙可见）——框选的时候要被墙/机器挡住的边界也能看见，
 * 关掉工具或按 Ctrl+V 就消失。不写深度、不写颜色之外的 GL 状态（pushAttrib 兜底）。
 */
public class RadarSelectionRenderer {

    private static final double EXPAND = 0.02D;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!RegionSelection.isActive() || !RegionSelection.isComplete()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.gameSettings.hideGUI) return;
        EntityClientPlayerMP player = mc.thePlayer;
        if (RegionSelection.dim() != player.dimension) return;

        int[] b = RegionSelection.box();
        if (b == null) return;

        float pt = event.partialTicks;
        double camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * pt;
        double camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * pt;
        double camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * pt;

        double x0 = b[0] - camX - EXPAND;
        double y0 = b[1] - camY - EXPAND;
        double z0 = b[2] - camZ - EXPAND;
        double x1 = b[3] + 1.0D - camX + EXPAND;
        double y1 = b[4] + 1.0D - camY + EXPAND;
        double z1 = b[5] + 1.0D - camZ + EXPAND;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2.0F);

        Tessellator tess = Tessellator.instance;

        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_I(0x4DD0E1, 200);
        boxEdges(tess, x0, y0, z0, x1, y1, z1);
        tess.draw();

        // 角点：每个角画一小段十字，方便在远处确认角点位置
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_I(0xFFFFFF, 235);
        double c = 0.35D;
        corner(tess, x0, y0, z0, c);
        corner(tess, x1, y0, z0, c);
        corner(tess, x0, y0, z1, c);
        corner(tess, x1, y0, z1, c);
        corner(tess, x0, y1, z0, c);
        corner(tess, x1, y1, z0, c);
        corner(tess, x0, y1, z1, c);
        corner(tess, x1, y1, z1, c);
        tess.draw();

        GL11.glLineWidth(1.0F);
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private static void boxEdges(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
        // 底面
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y0, z0);
        // 顶面
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y1, z0);
        // 立柱
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
    }

    private static void corner(Tessellator t, double x, double y, double z, double c) {
        t.addVertex(x - c, y, z);
        t.addVertex(x + c, y, z);
        t.addVertex(x, y - c, z);
        t.addVertex(x, y + c, z);
        t.addVertex(x, y, z - c);
        t.addVertex(x, y, z + c);
    }
}
