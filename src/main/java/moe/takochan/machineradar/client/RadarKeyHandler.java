package moe.takochan.machineradar.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import moe.takochan.machineradar.MachineRadar;
import moe.takochan.machineradar.client.gui.GuiEfficiency;
import moe.takochan.machineradar.client.gui.GuiGroupAssign;
import moe.takochan.machineradar.client.gui.GuiMachineMap;
import moe.takochan.machineradar.client.gui.GuiRadarSettings;
import moe.takochan.machineradar.config.RadarConfig;
import moe.takochan.machineradar.proxy.ClientProxy;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;
import moe.takochan.machineradar.radar.RegionSelection;

/**
 * K        = 开关
 * Ctrl+K   = 图层循环（全部 / 仅多方块 / 仅单方块）
 * Shift+K  = 导出 dump
 * L        = 工厂列表
 * N        = 2D 机器地图
 * H        = 效率心电图（准星机器 / 追踪机器）
 * M        = 设置界面
 * V        = 3D 选区：设角点1 → 设角点2 → 打开选区归组
 * Ctrl+V   = 取消 3D 选区
 */
public class RadarKeyHandler {

    private static final String HEAD = EnumChatFormatting.AQUA + "[MachineRadar] " + EnumChatFormatting.RESET;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc0 = Minecraft.getMinecraft();
        if (ClientProxy.KEY_SELECT != null && ClientProxy.KEY_SELECT.isPressed()) {
            if (mc0.thePlayer != null) handleSelectionKey(mc0);
            return;
        }
        if (ClientProxy.KEY_LIST != null && ClientProxy.KEY_LIST.isPressed()) {
            if (mc0.thePlayer != null) MachineRadar.proxy.openMachineList();
            return;
        }
        if (ClientProxy.KEY_MAP != null && ClientProxy.KEY_MAP.isPressed()) {
            if (mc0.thePlayer != null) mc0.displayGuiScreen(new GuiMachineMap());
            return;
        }
        if (ClientProxy.KEY_ECG != null && ClientProxy.KEY_ECG.isPressed()) {
            if (mc0.thePlayer == null) return;
            MachineRecord target = RadarRenderer.FOCUS;
            if (target == null) target = RadarState.tracked();
            if (target != null) mc0.displayGuiScreen(new GuiEfficiency(target));
            return;
        }
        if (ClientProxy.KEY_SETTINGS != null && ClientProxy.KEY_SETTINGS.isPressed()) {
            if (mc0.thePlayer != null) mc0.displayGuiScreen(new GuiRadarSettings());
            return;
        }
        if (ClientProxy.KEY_GROUP_LAYER != null && ClientProxy.KEY_GROUP_LAYER.isPressed()) {
            if (mc0.thePlayer == null) return;
            RadarState.groupLayer = !RadarState.groupLayer;
            if (RadarState.groupLayer) RadarState.enabled = true;
            RadarConfig.saveGroupLayer(RadarState.groupLayer);
            chat(
                mc0,
                RadarState.groupLayer ? EnumChatFormatting.GREEN + "分组图层：开" + EnumChatFormatting.GRAY + "（只看分区，不画机器）"
                    : EnumChatFormatting.GRAY + "分组图层：关");
            return;
        }
        if (ClientProxy.KEY_TOGGLE == null) return;
        if (!ClientProxy.KEY_TOGGLE.isPressed()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) {
            RadarState.layer = (RadarState.layer + 1) % 3;
            RadarConfig.saveLayer(RadarState.layer);
            chat(mc, EnumChatFormatting.WHITE + "图层：" + EnumChatFormatting.YELLOW + RadarState.layerName(RadarState.layer));
            return;
        }

        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            mc.thePlayer.sendChatMessage("/mradar dump");
            chat(mc, EnumChatFormatting.GRAY + "导出中…");
            return;
        }

        toggle(mc);
    }

    private void toggle(Minecraft mc) {
        RadarState.enabled = !RadarState.enabled;
        if (!RadarState.enabled) {
            RadarState.clearTracked();
            chat(mc, EnumChatFormatting.GRAY + "OFF");
            return;
        }

        List<MachineRecord> snap = RadarState.snapshot;
        int multi = 0;
        int single = 0;
        int unformed = 0;
        for (MachineRecord r : snap) {
            if (r.multi) {
                multi++;
                if (!r.assembled) unformed++;
            } else {
                single++;
            }
        }

        String body;
        if (snap.isEmpty()) {
            body = EnumChatFormatting.GREEN + "ON" + EnumChatFormatting.RESET + "  扫描中…（约 1 秒）";
        } else {
            body = EnumChatFormatting.GREEN + "ON"
                + EnumChatFormatting.RESET
                + "  多方块 "
                + multi
                + " / 单方块 "
                + single
                + (unformed > 0 ? " / 未成型 " + EnumChatFormatting.RED + unformed : "")
                + EnumChatFormatting.DARK_GRAY
                + "  ["
                + RadarState.layerName(RadarState.layer)
                + "]";
        }
        chat(mc, body);
    }

    private void chat(Minecraft mc, String msg) {
        mc.thePlayer.addChatMessage(new ChatComponentText(HEAD + msg));
    }

    // ------------------------------------------------------------------ 3D 选区

    /**
     * V 的语义：没框 → 设角点1；框了一半 → 设角点2 并直接打开归组界面；
     * 已经框完 → 重新打开归组界面（要重框先 Ctrl+V）。
     */
    private void handleSelectionKey(Minecraft mc) {
        if (mc.thePlayer == null) return;
        // 有界面开着时不要抢 V（避免在搜索框/设置里误触发）
        if (mc.currentScreen != null) return;
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        if (ctrl) {
            RegionSelection.clear();
            chat(mc, EnumChatFormatting.GRAY + "已取消 3D 选区");
            return;
        }

        int dim = mc.thePlayer.dimension;
        if (RegionSelection.isActive() && RegionSelection.dim() != dim) RegionSelection.clear();

        if (RegionSelection.isComplete()) {
            openRegionAssign(mc);
            return;
        }

        int[] target = lookedBlock(mc);
        if (target == null) {
            chat(mc, EnumChatFormatting.GRAY + "准星没对着方块：对着想要的角点再按 V");
            return;
        }

        if (!RegionSelection.isActive() || !RegionSelection.hasA()) {
            if (!RegionSelection.isActive()) RegionSelection.begin(dim);
            RegionSelection.cornerA(dim, target[0], target[1], target[2]);
            chat(
                mc,
                "选区角点1 = " + EnumChatFormatting.WHITE
                    + target[0]
                    + ", "
                    + target[1]
                    + ", "
                    + target[2]
                    + EnumChatFormatting.RESET
                    + "，再对着另一个角点按 V");
            return;
        }

        RegionSelection.cornerB(dim, target[0], target[1], target[2]);
        int n = RegionSelection.countMachines(dim);
        chat(
            mc,
            "选区 " + EnumChatFormatting.WHITE
                + RegionSelection.sizeText()
                + EnumChatFormatting.RESET
                + "，内含 "
                + EnumChatFormatting.YELLOW
                + n
                + EnumChatFormatting.RESET
                + " 台机器");
        openRegionAssign(mc);
    }

    private void openRegionAssign(Minecraft mc) {
        int dim = mc.thePlayer.dimension;
        mc.displayGuiScreen(new GuiGroupAssign(RegionSelection.machinesInside(dim), true, false, dim, RegionSelection.box()));
    }

    /** 准星指着的方块（objectMouseOver 优先，取不到时自己射线兜底） */
    private static int[] lookedBlock(Minecraft mc) {
        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            mop = mc.thePlayer.rayTrace(6.0D, 1.0F);
        }
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        return new int[] { mop.blockX, mop.blockY, mop.blockZ };
    }
}