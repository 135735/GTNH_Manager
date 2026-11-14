package moe.takochan.machineradar.radar;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import moe.takochan.machineradar.config.RadarConfig;

/**
 * 服务端扫描器。单机版把结果直接写进 RadarState（同进程），客户端渲染线程读快照。
 */
public class RadarScanner {

    private static final Logger LOG = LogManager.getLogger("MachineRadar");

    private static boolean firstTickLogged;
    private static boolean warned;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // 客户端 GUI 提交的远程开关机动作（单机同进程）
        RadarActions.process();

        RadarStats.serverTicks++;
        if (!firstTickLogged) {
            firstTickLogged = true;
            LOG.info("[MachineRadar] first ServerTickEvent received (radar={})", RadarState.enabled);
        }

        if (!RadarState.enabled) return;

        long now = System.currentTimeMillis();
        if (now - RadarStats.lastScanStartMs < Math.max(1, RadarConfig.refreshTicks) * 50L) return;
        scanNow();
    }

    /** 同步扫一次；命令 /machineradar scan 也走这里。 */
    public static synchronized void scanNow() {
        RadarStats.lastScanStartMs = System.currentTimeMillis();
        RadarStats.scans++;
        StructureCapture.beginScan();
        long t0 = System.nanoTime();
        int seen = 0;

        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return;
            WorldServer[] worlds = server.worldServers;
            if (worlds == null) return;

            final int range = RadarConfig.radius + 32;
            final long rangeSq = (long) range * (long) range;

            List<MachineRecord> out = new ArrayList<MachineRecord>(256);

            for (WorldServer world : worlds) {
                if (world == null) continue;
                List<EntityPlayerMP> players = playersOf(server, world);
                if (players.isEmpty()) continue;

                for (Object o : world.loadedTileEntityList) {
                    seen++;
                    if (!(o instanceof IGregTechTileEntity)) continue;
                    IGregTechTileEntity te = (IGregTechTileEntity) o;
                    int x = te.getXCoord();
                    int y = te.getYCoord();
                    int z = te.getZCoord();
                    if (!nearAny(players, x, y, z, rangeSq)) continue;

                    MachineRecord rec = RecordBuilder.build(te, world);
                    if (rec != null) out.add(rec);
                }
            }

            RadarState.snapshot = out;
            RadarState.lastScanAt = System.currentTimeMillis();
            RadarStats.lastTeSeen = seen;
            RadarStats.lastMachines = out.size();
            RadarStats.lastError = null;
            warned = false;
        } catch (Throwable t) {
            RadarStats.lastError = shortStack(t);
            RadarStats.lastTeSeen = seen;
            if (!warned) {
                warned = true;
                LOG.error("[MachineRadar] scan failed", t);
            }
        } finally {
            RadarStats.lastScanNanos = System.nanoTime() - t0;
        }
    }

    private static String shortStack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString()
            .split("\r?\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(6, lines.length); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    private static List<EntityPlayerMP> playersOf(MinecraftServer server, WorldServer world) {
        List<EntityPlayerMP> result = new ArrayList<EntityPlayerMP>(4);
        List<EntityPlayerMP> all = server.getConfigurationManager().playerEntityList;
        if (all == null) return result;
        for (EntityPlayerMP p : all) {
            if (p != null && p.worldObj == world) result.add(p);
        }
        return result;
    }

    private static boolean nearAny(List<EntityPlayerMP> players, int x, int y, int z, long rangeSq) {
        for (EntityPlayerMP p : players) {
            double dx = p.posX - (x + 0.5D);
            double dy = p.posY - (y + 0.5D);
            double dz = p.posZ - (z + 0.5D);
            if (dx * dx + dy * dy + dz * dz <= rangeSq) return true;
        }
        return false;
    }
}