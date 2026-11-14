package moe.takochan.machineradar.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import moe.takochan.machineradar.client.MachineGroups;
import moe.takochan.machineradar.config.RadarConfig;
import moe.takochan.machineradar.radar.HatchRef;
import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 世界内叠加层，三层表达：
 *
 * <ul>
 * <li>近处：半透明外壳填色（只填最外层，深度测试 + 背面剔除）+ 外轮廓线框；</li>
 * <li>远处：填色淡出，只留外轮廓；</li>
 * <li>轮廓：焦点机器的轮廓不写深度（X 光），非焦点轮廓开深度测试并按距离/状态淡化，
 * 所以复杂结构也不会糊成一片。</li>
 * </ul>
 *
 * 舱室默认只有焦点机器全显示，其余淡化/不显示；焦点机器同时显示舱室朝向字。
 */
public class RadarRenderer {

    private static final Logger LOG = LogManager.getLogger("MachineRadar");
    private static final double FACE_EXPAND = 0.002D;

    public static volatile MachineRecord FOCUS;

    private static final Map<int[][], HullMesh> HULLS = new IdentityHashMap<int[][], HullMesh>();

    private final List<MachineRecord> visible = new ArrayList<MachineRecord>(256);
    private final Comparator<MachineRecord> byDistance = new Comparator<MachineRecord>() {

        @Override
        public int compare(MachineRecord a, MachineRecord b) {
            return Double.compare(distSq(a), distSq(b));
        }
    };

    private double camX, camY, camZ;
    private double camEyeY;
    private int glErrors;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!RadarState.enabled) {
            FOCUS = null;
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null || mc.gameSettings.hideGUI) return;
        // 独立分组图层：只画分区区域 + 大标签，不画机器本体
        if (RadarState.groupLayer) {
            FOCUS = null;
            drawGroupLayer(mc, event.partialTicks);
            return;
        }
        if (RadarState.snapshot.isEmpty()) {
            FOCUS = null;
            return;
        }

        EntityClientPlayerMP player = mc.thePlayer;
        float pt = event.partialTicks;
        camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * pt;
        camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * pt;
        camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * pt;
        camEyeY = camY + player.getEyeHeight();

        collect(player, pt);
        if (visible.isEmpty()) {
            FOCUS = null;
            return;
        }
        Collections.sort(visible, byDistance);

        MachineRecord focus = pickFocus(player, pt);
        FOCUS = focus;

        RenderHelper.disableStandardItemLighting();
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);

        Tessellator tess = Tessellator.instance;

        drawFills(tess, focus);
        drawOutlines(tess, focus);
        drawGroupAreas(tess);
        drawHatches(tess, focus);
        drawMarkers(tess);
        drawIcons(mc, tess, focus);
        drawController(tess, focus);
        drawSecondaryControllers(tess, focus, player, pt);
        drawTracked(tess);
        drawTexts(mc, focus);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        int err = GL11.glGetError();
        if (err != 0 && glErrors < 5) {
            glErrors++;
            LOG.warn("[MachineRadar] GL error 0x{} while drawing radar overlay", Integer.toHexString(err));
        }
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------ 外壳填色

    private void drawFills(Tessellator tess, MachineRecord focus) {
        if (!RadarConfig.showFills) return;

        // 填色：开深度测试、关深度写入、背面剔除。
        // 结构方块本身是实心的，深度测试会挡住背面/内部的面；剔除再把朝外的背面扔掉，
        // 最后只剩"从玩家这侧能看到的、最外层的一层颜色"。
        boolean xray = RadarConfig.overlayXray;
        if (xray) GL11.glDisable(GL11.GL_DEPTH_TEST);
        else GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);

        tess.startDrawing(GL11.GL_QUADS);
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (rec == focus) continue;
            if (!drawable(rec)) continue;
            double d = dist(rec);
            if (d > RadarConfig.fillRadius) continue;
            int alpha = fillAlpha(rec, d, false);
            if (alpha < 3) continue;
            HullMesh hull = hullOf(rec);
            if (hull == null) continue;
            tess.setColorRGBA_I(rec.colorOf(), alpha);
            emitFaces(tess, hull);
        }
        tess.draw();

        if (focus != null && drawable(focus)) {
            double d = dist(focus);
            if (d <= RadarConfig.maxDistance) {
                HullMesh hull = hullOf(focus);
                if (hull != null) {
                    tess.startDrawing(GL11.GL_QUADS);
                    tess.setColorRGBA_I(focus.colorOf(), fillAlpha(focus, d, true));
                    emitFaces(tess, hull);
                    tess.draw();
                }
            }
        }

        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private int fillAlpha(MachineRecord rec, double d, boolean focus) {
        double fade = clamp(1.0D - d / Math.max(1.0D, RadarConfig.fillRadius), 0.0D, 1.0D);
        double a = alphaFor(rec, d) * (0.35D + 0.65D * fade);
        double scale = focus ? 1.6D : 1.0D;
        int alpha = (int) (RadarConfig.overlayAlpha * a * scale);
        return alpha < 0 ? 0 : (alpha > 210 ? 210 : alpha);
    }

    // ------------------------------------------------------------------ 线框

    private void drawOutlines(Tessellator tess, MachineRecord focus) {
        if (!RadarConfig.showWireframe) return;

        boolean xray = RadarConfig.wireframeXray;
        int limit = RadarConfig.wireframeMaxMachines;

        if (!xray) {
            // 深度预写：只写深度、不写颜色，给下面的轮廓线做遮挡测试。
            // 这样凹形结构/被前面机器挡住的后方轮廓会被 LEQUAL 深度测试剔除，
            // 不会再出现"背面杂线"。
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glColorMask(false, false, false, false);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            // 把预写深度稍微推远一点，避免和随后的轮廓线 z-fight
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(1.0F, 1.0F);

            tess.startDrawing(GL11.GL_QUADS);
            int written = 0;
            if (!RadarConfig.wireframeFocusOnly) {
                for (int i = 0; i < visible.size(); i++) {
                    MachineRecord rec = visible.get(i);
                    if (rec == focus || !drawable(rec)) continue;
                    if (dist(rec) > RadarConfig.wireframeRadius) continue;
                    if (limit > 0 && written >= limit) break;
                    HullMesh hull = hullOf(rec);
                    if (hull == null) continue;
                    written++;
                    emitFaces(tess, hull);
                }
            }
            if (focus != null && drawable(focus)) {
                HullMesh hull = hullOf(focus);
                if (hull != null) emitFaces(tess, hull);
            }
            tess.draw();

            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glDisable(GL11.GL_CULL_FACE);
        }

        // 非焦点：只画"从当前视角看得到的外轮廓"（轮廓边），并开深度测试——
        // 被墙/别的机器挡住的部分不画，所以复杂结构也不会再糊成一片。
        if (!RadarConfig.wireframeFocusOnly) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glLineWidth(1.0F);

            int drawn = 0;
            tess.startDrawing(GL11.GL_LINES);
            for (int i = 0; i < visible.size(); i++) {
                MachineRecord rec = visible.get(i);
                if (rec == focus || !drawable(rec)) continue;
                double d = dist(rec);
                if (d > RadarConfig.wireframeRadius) continue;
                // 按距离取最近的 N 台，bound 住每帧轮廓计算量（可配置，0=不限）
                if (limit > 0 && drawn >= limit) break;
                HullMesh hull = hullOf(rec);
                if (hull == null || hull.edges.length == 0) continue;
                drawn++;

                double fade = clamp(1.0D - d / Math.max(1.0D, RadarConfig.wireframeRadius), 0.15D, 1.0D);
                int alpha = (int) (RadarConfig.outlineAlpha * (0.35D + 0.45D * fade));
                if (alpha < 6) continue;
                tess.setColorRGBA_I(rec.colorOf(), Math.min(200, alpha));
                emitSilhouette(tess, hull);
            }
            tess.draw();
        }

        // 焦点：白色加粗；默认和普通线框一样遵守深度（凹形结构背面会被剔除），
        // 需要"隔墙找机器"时在设置里打开「线框 X 光」。
        if (focus != null && drawable(focus) && dist(focus) <= RadarConfig.wireframeRadius) {
            HullMesh hull = hullOf(focus);
            if (hull != null && hull.edges.length > 0) {
                if (xray) GL11.glDisable(GL11.GL_DEPTH_TEST);
                else GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glLineWidth(2.2F);
                tess.startDrawing(GL11.GL_LINES);
                tess.setColorRGBA_I(0xFFFFFF, 235);
                emitSilhouette(tess, hull);
                tess.draw();
                GL11.glLineWidth(1.0F);
            }
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------------ 分组图层

    /**
     * 只画分组：每个分组的区域框 + 放大的名字/台数标签。
     * 给"按分区看整体"用，机器本体完全不画，所以不会和普通线框糊在一起。
     */
    private void drawGroupLayer(Minecraft mc, float pt) {
        EntityClientPlayerMP player = mc.thePlayer;
        camX = player.lastTickPosX + (player.posX - player.lastTickPosX) * pt;
        camY = player.lastTickPosY + (player.posY - player.lastTickPosY) * pt;
        camZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * pt;
        camEyeY = camY + player.getEyeHeight();

        int dim = player.dimension;
        if (MachineGroups.GROUPS.isEmpty()) return;

        RenderHelper.disableStandardItemLighting();
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);
        GL11.glLineWidth(2.0F);

        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_LINES);
        for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
            MachineGroup group = MachineGroups.GROUPS.get(i);
            int[] b = MachineGroups.bounds(group, dim);
            if (b == null) continue;
            tess.setColorRGBA_I(group.color, 210);
            emitBox(
                tess,
                b[0] - camX - FACE_EXPAND,
                b[1] - camY - FACE_EXPAND,
                b[2] - camZ - FACE_EXPAND,
                b[3] + 1.0D - camX + FACE_EXPAND,
                b[4] + 1.0D - camY + FACE_EXPAND,
                b[5] + 1.0D - camZ + FACE_EXPAND);
        }
        tess.draw();
        GL11.glLineWidth(1.0F);

        // 大标签：比普通区域预览大一档，远距离也读得清
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
            MachineGroup group = MachineGroups.GROUPS.get(i);
            int[] b = MachineGroups.bounds(group, dim);
            if (b == null) continue;
            int online = MachineGroups.onlineCount(group, dim);
            int running = 0;
            List<MachineRecord> members = MachineGroups.onlineMembers(group);
            for (int j = 0; j < members.size(); j++) {
                if (members.get(j).status() == MachineRecord.RUNNING) running++;
            }
            double cx = (b[0] + b[3] + 1) * 0.5D;
            double cz = (b[2] + b[5] + 1) * 0.5D;
            double cy = b[4] + 2.4D;
            double dx = cx - camX;
            double dz = cz - camZ;
            double d = Math.sqrt(dx * dx + dz * dz);
            float scale = clampF((float) d * 0.0032F, 0.028F, 0.75F);
            drawWorldText(
                mc.fontRenderer,
                group.name + " · " + online + "台" + (running > 0 ? "（" + running + "运行）" : ""),
                cx,
                cy,
                cz,
                scale,
                group.color);
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------ 分组区域

    /**
     * 分组区域预览：给每个分组画一个整体 AABB（模式 2 会按 16 格区块对齐），
     * 用于"这片是哪个分区"的快速判断。基础版，后续再叠加区域统计。
     */
    private void drawGroupAreas(Tessellator tess) {
        int mode = RadarConfig.groupPreviewMode;
        if (mode <= 0) return;
        if (MachineGroups.GROUPS.isEmpty()) return;
        int dim = Minecraft.getMinecraft().thePlayer.dimension;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(1.6F);
        tess.startDrawing(GL11.GL_LINES);
        for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
            MachineGroup group = MachineGroups.GROUPS.get(i);
            int[] b = MachineGroups.bounds(group, dim);
            if (b == null) continue;
            if (mode == 2) b = chunkBounds(b);
            tess.setColorRGBA_I(group.color, 170);
            emitBox(
                tess,
                b[0] - camX,
                b[1] - camY,
                b[2] - camZ,
                b[3] + 1.0D - camX,
                b[4] + 1.0D - camY,
                b[5] + 1.0D - camZ);
        }
        tess.draw();
        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private static int[] chunkBounds(int[] b) {
        return new int[] { (b[0] >> 4) << 4, (b[1] >> 4) << 4, (b[2] >> 4) << 4,
            (((b[3] + 16) >> 4) << 4) - 1, (((b[4] + 16) >> 4) << 4) - 1, (((b[5] + 16) >> 4) << 4) - 1 };
    }

    private static void emitBox(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1) {
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

    // ------------------------------------------------------------------ 舱室

    private void drawHatches(Tessellator tess, MachineRecord focus) {
        if (!RadarConfig.showHatches) return;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        tess.startDrawing(GL11.GL_QUADS);
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (!rec.multi || rec.hatches.length == 0) continue;
            boolean isFocus = rec == focus;
            if (!isFocus) {
                if (RadarConfig.hatchFocusOnly) continue;
                if (distSq(rec) > (double) RadarConfig.hatchNearRadius * RadarConfig.hatchNearRadius) continue;
            }
            int alpha = isFocus ? 235 : RadarConfig.hatchOthersAlpha;
            if (alpha < 4) continue;
            for (int h = 0; h < rec.hatches.length; h++) {
                HatchRef hatch = rec.hatches[h];
                solidCube(
                    tess,
                    hatch.x + 0.5D - camX,
                    hatch.y + 0.5D - camY,
                    hatch.z + 0.5D - camZ,
                    isFocus ? 0.17D : 0.11D,
                    HatchRef.colorOf(hatch.kind),
                    alpha);
                if (isFocus) {
                    solidCube(
                        tess,
                        hatch.x + 0.5D - camX,
                        hatch.y + 0.5D - camY,
                        hatch.z + 0.5D - camZ,
                        0.06D,
                        0xFFFFFF,
                        Math.min(255, alpha + 20));
                }
            }
        }
        tess.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------------ 小点 / 未成型标记

    private void drawMarkers(Tessellator tess) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        tess.startDrawing(GL11.GL_QUADS);
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            double d = dist(rec);
            if (d > RadarConfig.singleRadius) continue;
            if (rec.multi) {
                if (drawable(rec)) continue;
                solidCube(
                    tess,
                    rec.x + 0.5D - camX,
                    rec.y + 0.5D - camY,
                    rec.z + 0.5D - camZ,
                    0.20D,
                    rec.colorOf(),
                    (int) (220.0D * markerAlpha(d)));
            } else {
                solidCube(
                    tess,
                    rec.x + 0.5D - camX,
                    rec.y + 0.5D - camY,
                    rec.z + 0.5D - camZ,
                    0.13D,
                    HatchRef.tierColor(rec.tier),
                    (int) (235.0D * markerAlpha(d)));
            }
        }
        tess.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private static double markerAlpha(double d) {
        double far = RadarConfig.singleRadius;
        if (d <= far * 0.7D) return 1.0D;
        double u = (d - far * 0.7D) / Math.max(0.001D, far * 0.3D);
        return Math.max(0.15D, 1.0D - u);
    }

    // ------------------------------------------------------------------ 图标

    private void drawIcons(Minecraft mc, Tessellator tess, MachineRecord focus) {
        if (!RadarConfig.showIcons) return;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            ItemStack stack = rec.icon;
            if (stack == null) continue;
            double d = dist(rec);
            if (d > RadarConfig.iconRadius && rec != focus) continue;

            int alpha = iconAlpha(d);
            if (alpha < 8) continue;

            double cx = (rec.minX + rec.maxX + 1) * 0.5D;
            double cz = (rec.minZ + rec.maxZ + 1) * 0.5D;
            double cy = rec.maxY + (rec.multi ? 1.9D : 1.35D);
            double size = clamp(d * 0.022D, 0.35D, 1.5D);
            if (rec == focus) size *= 1.25D;

            drawIcon(mc, tess, stack, cx, cy, cz, size, alpha);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private int iconAlpha(double d) {
        double r = RadarConfig.iconRadius;
        if (d <= r * 0.6D) return 235;
        double u = (d - r * 0.6D) / Math.max(0.001D, r * 0.4D);
        return (int) Math.max(20.0D, 235.0D * (1.0D - u));
    }

    private void drawIcon(Minecraft mc, Tessellator tess, ItemStack stack, double x, double y, double z, double size,
        int alpha) {
        IIcon icon = stack.getIconIndex();
        if (icon == null) return;

        mc.getTextureManager()
            .bindTexture(stack.getItemSpriteNumber() == 0 ? TextureMap.locationBlocksTexture : TextureMap.locationItemsTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        GL11.glPushMatrix();
        GL11.glTranslated(x - camX, y - camY, z - camZ);
        GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(RenderManager.instance.playerViewX, 1.0F, 0.0F, 0.0F);

        double s = size * 0.5D;
        tess.startDrawing(GL11.GL_QUADS);
        tess.setColorRGBA_I(0xFFFFFF, alpha);
        tess.addVertexWithUV(-s, s, 0.0D, icon.getMinU(), icon.getMinV());
        tess.addVertexWithUV(s, s, 0.0D, icon.getMaxU(), icon.getMinV());
        tess.addVertexWithUV(s, -s, 0.0D, icon.getMaxU(), icon.getMaxV());
        tess.addVertexWithUV(-s, -s, 0.0D, icon.getMinU(), icon.getMaxV());
        tess.draw();

        GL11.glPopMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    // ------------------------------------------------------------------ 控制器

    private void drawController(Tessellator tess, MachineRecord focus) {
        if (focus == null) return;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        tess.startDrawing(GL11.GL_QUADS);
        double pulse = 0.78D + 0.22D * Math.sin(System.currentTimeMillis() / 260.0D);
        solidCube(
            tess,
            focus.x + 0.5D - camX,
            focus.y + 0.5D - camY,
            focus.z + 0.5D - camZ,
            0.34D,
            focus.colorOf(),
            (int) (235.0D * pulse));
        solidCube(
            tess,
            focus.x + 0.5D - camX,
            focus.y + 0.5D - camY,
            focus.z + 0.5D - camZ,
            0.13D,
            0xFFFFFF,
            (int) (245.0D * pulse));
        tess.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * 准星方向上、被焦点机器挡在后面的其它主控：也给一个小方块提示（不抢焦点）。
     * 这就是"主控只挡它自己，不挡后面的机器"的补充：后面的机器仍然能被发现。
     */
    private void drawSecondaryControllers(Tessellator tess, MachineRecord focus, EntityClientPlayerMP player,
        float pt) {
        if (RadarConfig.secondaryConeDegrees <= 0) return;
        Vec3 look = player.getLook(pt);
        double eyeY = camY + player.getEyeHeight();
        double cos = Math.cos(Math.toRadians(RadarConfig.secondaryConeDegrees));

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        tess.startDrawing(GL11.GL_QUADS);
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            if (rec == focus || !rec.multi) continue;
            double dx = rec.x + 0.5D - camX;
            double dy = rec.y + 0.5D - eyeY;
            double dz = rec.z + 0.5D - camZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1.0D || d > RadarConfig.maxDistance) continue;
            if ((look.xCoord * dx + look.yCoord * dy + look.zCoord * dz) / d < cos) continue;

            int alpha = (int) (90.0D + 90.0D * clamp(1.0D - d / RadarConfig.maxDistance, 0.0D, 1.0D));
            solidCube(
                tess,
                rec.x + 0.5D - camX,
                rec.y + 0.5D - camY,
                rec.z + 0.5D - camZ,
                0.22D,
                rec.colorOf(),
                alpha);
        }
        tess.draw();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * 列表里点「追踪」的机器：整机 AABB 白色脉冲框，不参与深度测试，隔着墙也能看到。
     * 与焦点分开画，所以追踪一台机器不会改变准星的焦点判定。
     */
    private void drawTracked(Tessellator tess) {
        MachineRecord rec = RadarState.tracked();
        if (rec == null) return;
        if (rec.dim != Minecraft.getMinecraft().thePlayer.dimension) return;
        double d = dist(rec);
        if (d > RadarConfig.maxDistance * 1.5D) return;

        double pulse = 0.55D + 0.45D * Math.sin(System.currentTimeMillis() / 220.0D);
        double e = 0.03D;
        double x0 = rec.minX - camX - e;
        double y0 = rec.minY - camY - e;
        double z0 = rec.minZ - camZ - e;
        double x1 = rec.maxX + 1.0D - camX + e;
        double y1 = rec.maxY + 1.0D - camY + e;
        double z1 = rec.maxZ + 1.0D - camZ + e;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(3.0F);
        tess.startDrawing(GL11.GL_LINES);
        tess.setColorRGBA_I(0xFFFFFF, (int) (120.0D + 120.0D * pulse));

        // 底面
        tess.addVertex(x0, y0, z0);
        tess.addVertex(x1, y0, z0);
        tess.addVertex(x1, y0, z0);
        tess.addVertex(x1, y0, z1);
        tess.addVertex(x1, y0, z1);
        tess.addVertex(x0, y0, z1);
        tess.addVertex(x0, y0, z1);
        tess.addVertex(x0, y0, z0);
        // 顶面
        tess.addVertex(x0, y1, z0);
        tess.addVertex(x1, y1, z0);
        tess.addVertex(x1, y1, z0);
        tess.addVertex(x1, y1, z1);
        tess.addVertex(x1, y1, z1);
        tess.addVertex(x0, y1, z1);
        tess.addVertex(x0, y1, z1);
        tess.addVertex(x0, y1, z0);
        // 立柱
        tess.addVertex(x0, y0, z0);
        tess.addVertex(x0, y1, z0);
        tess.addVertex(x1, y0, z0);
        tess.addVertex(x1, y1, z0);
        tess.addVertex(x1, y0, z1);
        tess.addVertex(x1, y1, z1);
        tess.addVertex(x0, y0, z1);
        tess.addVertex(x0, y1, z1);
        tess.draw();

        GL11.glLineWidth(1.0F);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------------ 文字
    private void drawTexts(Minecraft mc, MachineRecord focus) {
        if (!RadarConfig.showLabels && !RadarConfig.showHatchFacing) return;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        FontRenderer fr = mc.fontRenderer;

        if (RadarConfig.showLabels) {
            int drawn = 0;
            for (int i = 0; i < visible.size(); i++) {
                MachineRecord rec = visible.get(i);
                if (rec == focus) continue;
                double d = dist(rec);
                if (d > RadarConfig.labelRadius) continue;
                if (!rec.multi && d > 24.0D) continue;
                if (drawn >= RadarConfig.maxLabels) break;
                drawLabel(fr, rec, false, d);
                drawn++;
            }
            if (focus != null) drawLabel(fr, focus, true, dist(focus));

            MachineRecord tracked = RadarState.tracked();
            if (tracked != null && tracked != focus) {
                double td = dist(tracked);
                if (td <= RadarConfig.maxDistance * 1.5D) {
                    float scale = clampF((float) td * 0.0016F, 0.010F, 0.36F);
                    drawWorldText(
                        fr,
                        "◎ " + tracked.name + "  " + (int) td + "m",
                        tracked.x + 0.5D,
                        tracked.maxY + 2.6D,
                        tracked.z + 0.5D,
                        scale,
                        0xFFF176);
                }
            }
        }

        // 分组区域预览的标签
        if (RadarConfig.groupPreviewMode > 0 && !MachineGroups.GROUPS.isEmpty()) {
            int dim = mc.thePlayer.dimension;
            for (int i = 0; i < MachineGroups.GROUPS.size(); i++) {
                MachineGroup group = MachineGroups.GROUPS.get(i);
                int[] b = MachineGroups.bounds(group, dim);
                if (b == null) continue;
                if (RadarConfig.groupPreviewMode == 2) b = chunkBounds(b);
                double cx = (b[0] + b[3] + 1) * 0.5D;
                double cz = (b[2] + b[5] + 1) * 0.5D;
                double cy = b[4] + 1.5D;
                double dx = cx - camX;
                double dz = cz - camZ;
                double d = Math.sqrt(dx * dx + dz * dz);
                float scale = clampF((float) d * 0.0022F, 0.020F, 0.55F);
                drawWorldText(
                    fr,
                    group.name + " · " + MachineGroups.onlineCount(group, dim) + "台",
                    cx,
                    cy,
                    cz,
                    scale,
                    group.color);
            }
        }

        // 舱室朝向：只在焦点机器上写，避免糊屏
        if (RadarConfig.showHatchFacing && focus != null && focus.hatches.length > 0) {
            double d = dist(focus);
            float scale = clampF((float) d * 0.0011F, 0.007F, 0.20F);
            for (int i = 0; i < focus.hatches.length; i++) {
                HatchRef h = focus.hatches[i];
                String face = HatchRef.facingName(h.facing);
                if (face.isEmpty()) continue;
                drawWorldText(
                    fr,
                    face,
                    h.x + 0.5D,
                    h.y + 1.35D,
                    h.z + 0.5D,
                    scale,
                    HatchRef.colorOf(h.kind));
            }
        }
    }

    private void drawLabel(FontRenderer fr, MachineRecord rec, boolean focus, double d) {
        double x = rec.x + 0.5 - camX;
        double y = rec.y + (focus ? 2.9D : 1.9D) - camY;
        double z = rec.z + 0.5 - camZ;

        String line1;
        String line2 = null;
        int color;
        float scale;

        if (focus) {
            line1 = rec.name + (rec.progress >= 0 ? "  " + (rec.progress / 10) + "%" : "");
            line2 = statusText(rec) + "  ·  " + hatchSummary(rec) + "  ·  " + (int) d + "m";
            color = 0xFFFFFF;
            scale = clampF((float) d * 0.0016F, 0.010F, 0.42F);
        } else {
            StringBuilder sb = new StringBuilder(rec.name);
            if (rec.progress >= 0) sb.append("  ")
                .append(rec.progress / 10)
                .append('%');
            if (rec.multi) sb.append("  ")
                .append(rec.hatches.length)
                .append('仓');
            sb.append("  ")
                .append((int) d);
            line1 = sb.toString();
            color = dim(rec.colorOf(), alphaFor(rec, d));
            scale = clampF((float) d * 0.0011F, 0.006F, 0.24F);
        }

        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(RenderManager.instance.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        int w = fr.getStringWidth(line1);
        if (focus) {
            fr.drawString(line1, -w / 2 + 1, 1, 0x000000);
            fr.drawString(line1, -w / 2, 0, color);
            if (line2 != null) {
                int w2 = fr.getStringWidth(line2);
                fr.drawString(line2, -w2 / 2 + 1, 11, 0x000000);
                fr.drawString(line2, -w2 / 2, 10, 0xCFD8DC);
            }
        } else {
            fr.drawStringWithShadow(line1, -w / 2, 0, color);
        }

        GL11.glPopMatrix();
    }

    private void drawWorldText(FontRenderer fr, String text, double x, double y, double z, float scale, int color) {
        GL11.glPushMatrix();
        GL11.glTranslated(x - camX, y - camY, z - camZ);
        GL11.glRotatef(-RenderManager.instance.playerViewY, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(RenderManager.instance.playerViewX, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-scale, -scale, scale);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        int w = fr.getStringWidth(text);
        fr.drawString(text, -w / 2 + 1, 1, 0x000000);
        fr.drawString(text, -w / 2, 0, color);
        GL11.glPopMatrix();
    }

    // ------------------------------------------------------------------ 几何

    /** 是否有可画的外壳几何。null 安全：准星没对着机器时 focus 就是 null。 */
    private static boolean drawable(MachineRecord rec) {
        return rec != null && rec.multi && rec.blocks != null && rec.blocks.length > 0;
    }

    private static HullMesh hullOf(MachineRecord rec) {
        HullMesh mesh = HULLS.get(rec.blocks);
        if (mesh != null) return mesh;
        try {
            mesh = HullMesh.of(rec.blocks);
        } catch (Throwable t) {
            LOG.warn("[MachineRadar] hull build failed", t);
            return null;
        }
        if (HULLS.size() > 256) HULLS.clear();
        HULLS.put(rec.blocks, mesh);
        return mesh;
    }

    private void emitFaces(Tessellator t, HullMesh hull) {
        int[] f = hull.faces;
        for (int i = 0; i < f.length; i += 4) {
            int bx = f[i], by = f[i + 1], bz = f[i + 2];
            double x0 = bx - camX - FACE_EXPAND;
            double y0 = by - camY - FACE_EXPAND;
            double z0 = bz - camZ - FACE_EXPAND;
            double x1 = bx + 1.0D - camX + FACE_EXPAND;
            double y1 = by + 1.0D - camY + FACE_EXPAND;
            double z1 = bz + 1.0D - camZ + FACE_EXPAND;

            switch (f[i + 3]) {
                case 0:
                    t.addVertex(x0, y0, z0);
                    t.addVertex(x0, y0, z1);
                    t.addVertex(x0, y1, z1);
                    t.addVertex(x0, y1, z0);
                    break;
                case 1:
                    t.addVertex(x1, y0, z0);
                    t.addVertex(x1, y1, z0);
                    t.addVertex(x1, y1, z1);
                    t.addVertex(x1, y0, z1);
                    break;
                case 2:
                    t.addVertex(x0, y0, z0);
                    t.addVertex(x1, y0, z0);
                    t.addVertex(x1, y0, z1);
                    t.addVertex(x0, y0, z1);
                    break;
                case 3:
                    t.addVertex(x0, y1, z0);
                    t.addVertex(x0, y1, z1);
                    t.addVertex(x1, y1, z1);
                    t.addVertex(x1, y1, z0);
                    break;
                case 4:
                    t.addVertex(x0, y0, z0);
                    t.addVertex(x0, y1, z0);
                    t.addVertex(x1, y1, z0);
                    t.addVertex(x1, y0, z0);
                    break;
                default:
                    t.addVertex(x0, y0, z1);
                    t.addVertex(x1, y0, z1);
                    t.addVertex(x1, y1, z1);
                    t.addVertex(x0, y1, z1);
                    break;
            }
        }
    }

    /** 面朝向：0=-X 1=+X 2=-Y 3=+Y 4=-Z 5=+Z */
    private static final double[][] FACE_NORMAL = { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 },
        { 0, 0, -1 }, { 0, 0, 1 } };

    /**
     * 只发送"外轮廓边"：一条边被两个外表面共用时，若这两个面一个朝向摄像机、一个背向摄像机，
     * 它就是当前视角下的轮廓线；边界边（只有一个面用）一律算轮廓。
     * 这样无论结构多复杂都只画一圈外轮廓，不会再出现每个方块一圈线。
     */
    private void emitSilhouette(Tessellator t, HullMesh hull) {
        int[] e = hull.edges;
        int[] dirs = hull.edgeDirs;
        int[] faceCount = hull.edgeFaces;
        for (int i = 0, k = 0; i < e.length; i += 6, k++) {
            int bits = dirs[k];
            int count = faceCount[k];
            if (count > 2) {
                // 非流形角：画出来当地标，反正很少
            } else if (count == 2) {
                if (Integer.bitCount(bits) <= 1) continue; // 共面相邻：不是轮廓，跳过
                int a = Integer.numberOfTrailingZeros(bits);
                int b = Integer.numberOfTrailingZeros(bits & (bits - 1));
                double mx = (e[i] + e[i + 3]) * 0.5D - camX;
                double my = (e[i + 1] + e[i + 4]) * 0.5D - camEyeY;
                double mz = (e[i + 2] + e[i + 5]) * 0.5D - camZ;

                boolean fa = mx * FACE_NORMAL[a][0] + my * FACE_NORMAL[a][1] + mz * FACE_NORMAL[a][2] > 0.0D;
                boolean fb = mx * FACE_NORMAL[b][0] + my * FACE_NORMAL[b][1] + mz * FACE_NORMAL[b][2] > 0.0D;
                if (fa == fb) continue;
            } else if (count == 1) {
                // 结构被截断（MAX_FACES）留下的开边：画
            } else {
                continue;
            }
            t.addVertex(e[i] - camX, e[i + 1] - camY, e[i + 2] - camZ);
            t.addVertex(e[i + 3] - camX, e[i + 4] - camY, e[i + 5] - camZ);
        }
    }

    private static void solidCube(Tessellator t, double cx, double cy, double cz, double half, int rgb, int alpha) {
        if (alpha <= 0) return;
        t.setColorRGBA_I(rgb, alpha > 255 ? 255 : alpha);
        double x0 = cx - half, y0 = cy - half, z0 = cz - half;
        double x1 = cx + half, y1 = cy + half, z1 = cz + half;

        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y0, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x1, y1, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z0);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y1, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x0, y0, z1);
        t.addVertex(x0, y1, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y0, z1);
        t.addVertex(x0, y0, z0);
        t.addVertex(x0, y1, z0);
        t.addVertex(x0, y1, z1);
        t.addVertex(x0, y0, z1);
        t.addVertex(x1, y0, z0);
        t.addVertex(x1, y0, z1);
        t.addVertex(x1, y1, z1);
        t.addVertex(x1, y1, z0);
    }

    // ------------------------------------------------------------------ 收集 / 焦点

    private void collect(EntityClientPlayerMP player, float pt) {
        visible.clear();
        double maxD = Math.min(RadarConfig.radius, RadarConfig.maxDistance);
        double maxSq = maxD * maxD;
        int dim = player.dimension;
        int layer = RadarState.layer;
        Vec3 look = player.getLook(pt);

        List<MachineRecord> all = RadarState.snapshot;
        for (int i = 0; i < all.size(); i++) {
            MachineRecord rec = all.get(i);
            if (rec.dim != dim) continue;
            if (layer == RadarState.LAYER_MULTI && !rec.multi) continue;
            if (layer == RadarState.LAYER_SINGLE && rec.multi) continue;

            double dx = rec.x + 0.5 - camX;
            double dy = rec.y + 0.5 - camY;
            double dz = rec.z + 0.5 - camZ;
            if (dx * dx + dy * dy + dz * dz > maxSq) continue;
            if (look.xCoord * dx + look.yCoord * dy + look.zCoord * dz < 0) continue;
            visible.add(rec);
        }
    }

    /**
     * 焦点判定：先对整机 AABB 做射线相交（大机器瞄准外壳任意一处都能选中），
     * 没打中任何盒子再退回「准星锥 + 控制器」。
     */
    private MachineRecord pickFocus(EntityClientPlayerMP player, float pt) {
        Vec3 look = player.getLook(pt);
        double eyeY = camY + player.getEyeHeight();
        double maxT = RadarConfig.maxDistance;

        double bestT = Double.MAX_VALUE;
        MachineRecord found = null;
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            double t = rayBox(
                camX,
                eyeY,
                camZ,
                look.xCoord,
                look.yCoord,
                look.zCoord,
                rec.minX - 0.05D,
                rec.minY - 0.05D,
                rec.minZ - 0.05D,
                rec.maxX + 1.05D,
                rec.maxY + 1.05D,
                rec.maxZ + 1.05D,
                maxT);
            if (t < 0.0D || t >= bestT) continue;
            bestT = t;
            found = rec;
        }
        if (found != null) return found;

        double cos = Math.cos(Math.toRadians(RadarConfig.coneDegrees));
        double best = Double.MAX_VALUE;
        for (int i = 0; i < visible.size(); i++) {
            MachineRecord rec = visible.get(i);
            double dx = rec.x + 0.5 - camX;
            double dy = rec.y + 0.5 - eyeY;
            double dz = rec.z + 0.5 - camZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1.0E-4) continue;
            double dot = (look.xCoord * dx + look.yCoord * dy + look.zCoord * dz) / d;
            if (dot < cos) continue;
            if (d < best) {
                best = d;
                found = rec;
            }
        }
        return found;
    }

    private static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, double x0,
        double y0, double z0, double x1, double y1, double z1, double maxT) {
        double tmin = 0.0D;
        double tmax = maxT;

        double[] origin = { ox, oy, oz };
        double[] dir = { dx, dy, dz };
        double[] lo = { x0, y0, z0 };
        double[] hi = { x1, y1, z1 };

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1.0E-8D) {
                if (origin[axis] < lo[axis] || origin[axis] > hi[axis]) return -1.0D;
                continue;
            }
            double inv = 1.0D / dir[axis];
            double t1 = (lo[axis] - origin[axis]) * inv;
            double t2 = (hi[axis] - origin[axis]) * inv;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return -1.0D;
        }
        return tmin;
    }

    // ------------------------------------------------------------------ 数值

    private static double alphaFor(MachineRecord rec, double d) {
        double r = radius(rec);
        double u = (d / r - RadarConfig.fadeNear) / Math.max(0.001D, RadarConfig.fadeFar - RadarConfig.fadeNear);
        u = clamp(u, 0.0D, 1.0D);
        return clamp(RadarConfig.fadeFloor + (1.0D - RadarConfig.fadeFloor) * Math.pow(1.0D - u, 1.7D), 0.0D, 1.0D);
    }

    private static double radius(MachineRecord rec) {
        if (!rec.multi) return 0.6D;
        double dx = rec.maxX - rec.minX + 1.0D;
        double dy = rec.maxY - rec.minY + 1.0D;
        double dz = rec.maxZ - rec.minZ + 1.0D;
        return Math.max(0.8D, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2.0D);
    }

    private static String statusText(MachineRecord rec) {
        return rec.statusText();
    }

    private static String hatchSummary(MachineRecord rec) {
        if (!rec.multi) return "—";
        int in = rec.hatchCount(HatchRef.ITEM_IN) + rec.hatchCount(HatchRef.FLUID_IN);
        int out = rec.hatchCount(HatchRef.ITEM_OUT) + rec.hatchCount(HatchRef.FLUID_OUT);
        int eu = rec.hatchCount(HatchRef.ENERGY_IN);
        int maint = rec.hatchCount(HatchRef.MAINTENANCE);

        StringBuilder sb = new StringBuilder();
        if (in > 0) sb.append("入")
            .append(in);
        if (out > 0) sb.append(sb.length() > 0 ? " " : "")
            .append("出")
            .append(out);
        if (eu > 0) sb.append(sb.length() > 0 ? " " : "")
            .append("电")
            .append(eu);
        if (maint > 0) sb.append(sb.length() > 0 ? " " : "")
            .append("维")
            .append(maint);
        return sb.length() == 0 ? "无舱室" : sb.toString();
    }

    private static int dim(int rgb, double a) {
        double f = 0.45D + 0.55D * a;
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static float clampF(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private double dist(MachineRecord rec) {
        return Math.sqrt(distSq(rec));
    }

    private double distSq(MachineRecord rec) {
        double dx = rec.x + 0.5 - camX;
        double dy = rec.y + 0.5 - camY;
        double dz = rec.z + 0.5 - camZ;
        return dx * dx + dy * dy + dz * dz;
    }
}