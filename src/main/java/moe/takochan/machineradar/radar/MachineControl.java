package moe.takochan.machineradar.radar;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 远程开关机：只碰 GT 的公开接口 {@code enableWorking()/disableWorking()}（软锤语义），
 * 不直接改结构状态，所以不会把机器标成未成型。
 *
 * <p>只在服务端线程调用（见 {@link RadarActions}）。
 */
public final class MachineControl {

    private static final Logger LOG = LogManager.getLogger("MachineRadar");

    private MachineControl() {}

    /** @return 是否找到并操作了 GT 机器 */
    public static boolean setWorking(World world, int x, int y, int z, boolean working) {
        if (world == null) return false;
        try {
            TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof IGregTechTileEntity)) return false;
            IGregTechTileEntity gte = (IGregTechTileEntity) te;
            if (working) gte.enableWorking();
            else gte.disableWorking();
            return true;
        } catch (Throwable t) {
            LOG.warn("[MachineRadar] remote working toggle failed at {} {} {}: {}", x, y, z, t.toString());
            return false;
        }
    }

    public static boolean isWorking(World world, int x, int y, int z) {
        if (world == null) return true;
        try {
            TileEntity te = world.getTileEntity(x, y, z);
            if (!(te instanceof IGregTechTileEntity)) return true;
            return ((IGregTechTileEntity) te).isAllowedToWork();
        } catch (Throwable t) {
            return true;
        }
    }
}
