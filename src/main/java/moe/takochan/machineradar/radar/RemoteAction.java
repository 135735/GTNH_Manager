package moe.takochan.machineradar.radar;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * 在服务端线程把一台机器切到指定工作状态。
 */
public final class RemoteAction implements Runnable {

    private final int dim, x, y, z;
    private final boolean working;

    public RemoteAction(MachineRecord rec, boolean working) {
        this.dim = rec.dim;
        this.x = rec.x;
        this.y = rec.y;
        this.z = rec.z;
        this.working = working;
    }

    @Override
    public void run() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        WorldServer world = server.worldServerForDimension(dim);
        if (world == null) return;
        MachineControl.setWorking(world, x, y, z, working);
    }
}
