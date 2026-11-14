package moe.takochan.machineradar.client;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 客户端 1 秒一次的历史采样节拍（给 MachineHistory 用）。
 */
public class RadarClientTicker {

    private int ticks;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < 20) return;
        ticks = 0;
        MachineHistory.sample();
        ProductionLedger.tick();
    }
}
