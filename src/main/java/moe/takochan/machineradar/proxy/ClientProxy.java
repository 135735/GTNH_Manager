package moe.takochan.machineradar.proxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import moe.takochan.machineradar.client.RadarClientTicker;
import moe.takochan.machineradar.client.RadarHud;
import moe.takochan.machineradar.client.RadarKeyHandler;
import moe.takochan.machineradar.client.RadarRenderer;
import moe.takochan.machineradar.client.RadarSelectionRenderer;
import moe.takochan.machineradar.client.WandInteractionHandler;
import moe.takochan.machineradar.client.gui.GuiGroupAssign;
import moe.takochan.machineradar.client.gui.GuiMachineList;
import moe.takochan.machineradar.radar.RegionSelection;

public class ClientProxy extends CommonProxy {

    public static KeyBinding KEY_TOGGLE;
    public static KeyBinding KEY_SETTINGS;
    public static KeyBinding KEY_LIST;
    public static KeyBinding KEY_MAP;
    public static KeyBinding KEY_ECG;
    public static KeyBinding KEY_SELECT;
    public static KeyBinding KEY_GROUP_LAYER;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        // 物品贴图只有客户端需要（setTextureName 是 @SideOnly(CLIENT)）
        if (RADAR_WAND != null) RADAR_WAND.setTextureName("machineradar:radar_wand");
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);

        KEY_TOGGLE = new KeyBinding("key.machineradar.toggle", Keyboard.KEY_K, "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_TOGGLE);
        KEY_SETTINGS = new KeyBinding("key.machineradar.settings", Keyboard.KEY_M, "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_SETTINGS);
        KEY_LIST = new KeyBinding("key.machineradar.list", Keyboard.KEY_L, "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_LIST);
        KEY_MAP = new KeyBinding("key.machineradar.map", Keyboard.KEY_N, "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_MAP);
        KEY_ECG = new KeyBinding("key.machineradar.ecg", Keyboard.KEY_H, "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_ECG);
        KEY_SELECT = new KeyBinding("key.machineradar.select", Keyboard.KEY_V, "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_SELECT);
        KEY_GROUP_LAYER = new KeyBinding(
            "key.machineradar.grouplayer",
            Keyboard.KEY_G,
            "key.categories.machineradar");
        ClientRegistry.registerKeyBinding(KEY_GROUP_LAYER);

        FMLCommonHandler.instance().bus().register(new RadarKeyHandler());
        FMLCommonHandler.instance().bus().register(new RadarClientTicker());
        // 框选杖同时监听 Forge 的 PlayerInteractEvent 和 FML 的 MouseInputEvent，两条总线都挂
        WandInteractionHandler wand = new WandInteractionHandler();
        FMLCommonHandler.instance().bus().register(wand);
        MinecraftForge.EVENT_BUS.register(wand);
        MinecraftForge.EVENT_BUS.register(new RadarRenderer());
        MinecraftForge.EVENT_BUS.register(new RadarSelectionRenderer());
        MinecraftForge.EVENT_BUS.register(new RadarHud());
    }

    @Override
    public void openMachineList() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        // 单机里服务端命令也会走到这里（ServerTick 线程），切回客户端线程再开界面
        mc.func_152344_a(new Runnable() {

            @Override
            public void run() {
                Minecraft client = Minecraft.getMinecraft();
                if (client == null || client.thePlayer == null) return;
                client.displayGuiScreen(new GuiMachineList());
            }
        });
    }

    @Override
    public void openRegionAssign() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.func_152344_a(new Runnable() {

            @Override
            public void run() {
                Minecraft client = Minecraft.getMinecraft();
                if (client == null || client.thePlayer == null) return;
                if (client.currentScreen != null) return;
                if (!RegionSelection.isComplete()) return;
                int dim = client.thePlayer.dimension;
                if (RegionSelection.dim() != dim) return;
                client.displayGuiScreen(
                    new GuiGroupAssign(RegionSelection.machinesInside(dim), true, false, dim, RegionSelection.box()));
            }
        });
    }
}