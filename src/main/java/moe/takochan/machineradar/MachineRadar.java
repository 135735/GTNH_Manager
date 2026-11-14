package moe.takochan.machineradar;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import moe.takochan.machineradar.command.CommandRadar;
import moe.takochan.machineradar.config.RadarConfig;
import moe.takochan.machineradar.proxy.CommonProxy;

/**
 * Machine Radar - 在 GTNH 里给每台 GT 机器（单方块 / 多方块）打标签、画舱室、报状态。
 * 目前是单机版：扫描跑在整合服务端的 ServerTick 上，客户端直接读同进程的快照。
 */
@Mod(modid = MachineRadar.MODID, name = "Machine Radar", version = "0.11.0", acceptableRemoteVersions = "*")
public class MachineRadar {

    public static final String MODID = "machineradar";

    @Mod.Instance(MODID)
    public static MachineRadar INSTANCE;

    @SidedProxy(
        clientSide = "moe.takochan.machineradar.proxy.ClientProxy",
        serverSide = "moe.takochan.machineradar.proxy.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        RadarConfig.load(event.getSuggestedConfigurationFile());
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandRadar());
    }
}
