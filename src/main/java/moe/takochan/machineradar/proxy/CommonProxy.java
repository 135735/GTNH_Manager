package moe.takochan.machineradar.proxy;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import moe.takochan.machineradar.item.ItemRadarWand;
import moe.takochan.machineradar.radar.RadarScanner;
import moe.takochan.machineradar.radar.StructureCapture;

public class CommonProxy {

    /** 框选杖（服务端也要注册，物品本身只在客户端有行为） */
    public static Item RADAR_WAND;

    public void preInit(FMLPreInitializationEvent event) {
        RADAR_WAND = new ItemRadarWand();
        GameRegistry.registerItem(RADAR_WAND, "radar_wand");
        // 便宜好做的配方；不想要就把这两行删掉，用 /give 或整合包配方
        GameRegistry.addShapelessRecipe(new ItemStack(RADAR_WAND), Items.stick, Items.redstone, Items.gold_ingot);
    }

    public void init(FMLInitializationEvent event) {
        // 1.7.10 里 TickEvent 走 FML 总线；顺手也挂到 Forge 总线，两条路都收不到才是真问题
        RadarScanner scanner = new RadarScanner();
        FMLCommonHandler.instance().bus().register(scanner);
        MinecraftForge.EVENT_BUS.register(scanner);

        // StructureLib 的结构遍历事件（服务端线程）
        StructureCapture.register();
    }

    /** 打开工厂列表界面；纯服务端没有界面，默认什么也不做。 */
    public void openMachineList() {}

    /** 用当前 3D 选区打开归组界面；纯服务端没有界面，默认什么也不做。 */
    public void openRegionAssign() {}
}
