package moe.takochan.machineradar.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

/**
 * 雷达框选杖：类似创世神的选区工具，但一次性只框一个长方体区域。
 *
 * <p>拿着它右键方块（{@code WandInteractionHandler} 会取消原版右键，避免误开机器 GUI）：
 * 第一次设角点 1，第二次设角点 2 并自动打开归组界面（新建分组 / 加入 / 替换已有分组）。
 * 右键空气重新打开归组界面；潜行 + 右键清除选区。
 *
 * <p>行为全部在客户端事件里（{@code WandInteractionHandler}），物品本身只是「手上拿着哪个工具」
 * 的判定物，不实现 {@code onItemUse}：那条路在单机里会走到服务端线程。
 */
public class ItemRadarWand extends Item {

    public ItemRadarWand() {
        setMaxStackSize(1);
        setUnlocalizedName("machineradar.radar_wand");
        setCreativeTab(CreativeTabs.tabTools);
        // 贴图名在 ClientProxy.preInit 里设置：setTextureName 是 @SideOnly(CLIENT)
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void addInformation(ItemStack stack, EntityPlayer player, List list, boolean advanced) {
        list.add(EnumChatFormatting.GRAY + "右键方块：设角点 1 / 角点 2");
        list.add(EnumChatFormatting.GRAY + "框完后自动打开归组界面（新建 / 加入 / 替换）");
        list.add(EnumChatFormatting.GRAY + "右键空气：重新打开归组界面");
        list.add(EnumChatFormatting.GRAY + "潜行 + 右键：清除选区");
    }
}
