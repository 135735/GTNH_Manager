package moe.takochan.machineradar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import org.lwjgl.input.Mouse;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import moe.takochan.machineradar.MachineRadar;
import moe.takochan.machineradar.item.ItemRadarWand;
import moe.takochan.machineradar.radar.RegionSelection;

/**
 * 框选杖的世界交互：拿着杖右键方块 = 设下一个角点，并**取消这次原版右键**。
 *
 * <p>1.7.10 的客户端右键顺序是
 * {@code Minecraft.func_147121_ag} → {@code ForgeEventFactory.onPlayerInteract(RIGHT_CLICK_BLOCK)}
 * → {@code PlayerControllerMP.onPlayerRightClick}（开机器 GUI / 放方块都在这之后），
 * 所以在这里 cancel 掉就只设角点，不会顺手把机器界面点开。
 */
public class WandInteractionHandler {

    @SubscribeEvent
    public void onInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (event.world == null || !event.world.isRemote) return;

        EntityPlayer player = event.entityPlayer;
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc == null || player != mc.thePlayer) return;

        ItemStack held = player.getCurrentEquippedItem();
        if (held == null || !(held.getItem() instanceof ItemRadarWand)) return;

        event.setCanceled(true);

        int dim = player.dimension;
        if (player.isSneaking()) {
            RegionSelection.clear();
            message(player, EnumChatFormatting.GRAY + "已清除 3D 选区");
            return;
        }

        boolean fresh = !RegionSelection.isActive() || RegionSelection.dim() != dim
            || RegionSelection.isComplete()
            || !RegionSelection.hasA();
        if (fresh) {
            if (!RegionSelection.isActive() || RegionSelection.dim() != dim) RegionSelection.begin(dim);
            RegionSelection.cornerA(dim, event.x, event.y, event.z);
            message(
                player,
                "角点1 = " + EnumChatFormatting.WHITE
                    + event.x
                    + ", "
                    + event.y
                    + ", "
                    + event.z
                    + EnumChatFormatting.RESET
                    + "，再右键另一个方块设角点2");
            return;
        }

        RegionSelection.cornerB(dim, event.x, event.y, event.z);
        int n = RegionSelection.countMachines(dim);
        message(
            player,
            "选区 " + EnumChatFormatting.WHITE
                + RegionSelection.sizeText()
                + EnumChatFormatting.RESET
                + "，内含 "
                + EnumChatFormatting.YELLOW
                + n
                + EnumChatFormatting.RESET
                + " 台机器");
        MachineRadar.proxy.openRegionAssign();
    }

    private static void message(EntityPlayer player, String msg) {
        player.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[MachineRadar] " + msg));
    }

    /**
     * 右键空气：重新打开归组界面 / 清除选区。
     *
     * <p>没对着方块时原版不会发 {@link PlayerInteractEvent}，所以这里直接读鼠标事件；
     * 对着方块的情况已经在 {@link #onInteract} 里处理，这里跳过避免双重弹窗。
     */
    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
        if (!Mouse.getEventButtonState() || Mouse.getEventButton() != 1) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.currentScreen != null) return;
        if (mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.MISS) {
            return;
        }

        ItemStack held = mc.thePlayer.getCurrentEquippedItem();
        if (held == null || !(held.getItem() instanceof ItemRadarWand)) return;

        int dim = mc.thePlayer.dimension;
        if (mc.thePlayer.isSneaking()) {
            RegionSelection.clear();
            message(mc.thePlayer, EnumChatFormatting.GRAY + "已清除 3D 选区");
            return;
        }
        if (RegionSelection.isComplete() && RegionSelection.dim() == dim) {
            MachineRadar.proxy.openRegionAssign();
            return;
        }
        if (RegionSelection.isActive() && RegionSelection.hasA() && RegionSelection.dim() == dim) {
            message(
                mc.thePlayer,
                "角点1 = " + EnumChatFormatting.WHITE
                    + RegionSelection.ax()
                    + ", "
                    + RegionSelection.ay()
                    + ", "
                    + RegionSelection.az()
                    + EnumChatFormatting.RESET
                    + "，对着对角方块右键");
        } else {
            message(mc.thePlayer, EnumChatFormatting.GRAY + "对着一个方块右键设角点1，再对另一个角右键设角点2");
        }
    }
}
