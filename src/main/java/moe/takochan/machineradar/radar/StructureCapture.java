package moe.takochan.machineradar.radar;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.gtnewhorizon.structurelib.StructureEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/**
 * 用 GT 自己的结构校验枚举多方块的方块集合。
 *
 * <p>原理：GT 的每一台多方块在 {@code checkStructure()} 里都会调用 {@code checkMachine()}，
 * 而后者最终会走到 StructureLib 的 {@code IStructureDefinition.iterate(...)}。这个 iterate 用的是
 * 机器自己写在 {@code checkPiece(piece, offsetA, offsetB, offsetC)} 里的锚点偏移——也就是唯一
 * 权威的「控制器在形状里的位置」。我们不需要去猜这个偏移，只要在校验期间打开 StructureLib 的
 * 仪表（{@link StructureLibAPI#enableInstrument(Object)}），StructureLib 就会对每个被访问的
 * 结构单元发出 {@link StructureEvent.StructureElementVisitedEvent}，事件里带的是**绝对世界坐标**。
 *
 * <p>于是：让 GT 校验一次 → 收集事件坐标 → 就是这台机器结构定义里的全部格子（外壳、线圈、
 * 各级玻璃、舱室、控制器）。不需要 bbox、不需要 BFS、不需要判断方块材质，也不会被旋转/镜像
 * 影响，因为这些坐标本来就是 GT 按当前 {@code ExtendedFacing} 算出来的。
 *
 * <p>只在服务端线程调用；单机 integrated server 与客户端同进程。
 */
public final class StructureCapture {

    /** 同一台机器多久重新校验一次（结构方块缓存） */
    private static final long TTL_MS = 120_000L;
    /** 校验失败（未成型）时隔多久重试 */
    private static final long FAIL_TTL_MS = 5_000L;
    /** 防止异常结构把内存打爆 */
    private static final int MAX_BLOCKS = 32_768;

    private static final Logger LOG = LogManager.getLogger("MachineRadar");
    private static final Set<String> LOGGED = new HashSet<String>();

    private static final Object TOKEN = new Object();
    private static final Map<String, Entry> CACHE = new HashMap<String, Entry>();
    private static final StructureCapture LISTENER = new StructureCapture();

    private static World activeWorld;
    private static final Set<Long> activeBlocks = new HashSet<Long>();
    private static boolean active;

    private static String lastError = "";
    private static int captures;
    private static int failures;
    /** 每次扫描最多做几次结构校验，避免一次性卡住服务端 tick */
    private static int budget;

    private StructureCapture() {}

    /** RadarScanner 每次扫描开始时调用，重置本帧的结构校验预算 */
    public static void beginScan() {
        budget = 8;
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(LISTENER);
    }

    @SubscribeEvent
    public void onStructureElementVisited(StructureEvent.StructureElementVisitedEvent event) {
        if (!active) return;
        if (event.getInstrumentIdentifier() != TOKEN) return;
        if (event.getWorld() != activeWorld) return;
        if (activeBlocks.size() >= MAX_BLOCKS) return;
        activeBlocks.add(pack(event.getX(), event.getY(), event.getZ()));
    }

    /**
     * @return 结构方块坐标数组（每项 {x,y,z}），未成型或采集失败返回 null。
     */
    public static int[][] capture(MTEMultiBlockBase machine, IGregTechTileEntity te, World world) {
        String key = key(world, te);
        long now = System.currentTimeMillis();
        Entry entry = CACHE.get(key);
        if (entry != null) {
            long ttl = entry.blocks != null ? TTL_MS : FAIL_TTL_MS;
            if (now - entry.at < ttl) return entry.blocks;
        }

        if (budget <= 0) {
            // 本帧预算用完了：有旧结果就继续用，别把好数据冲掉
            return entry == null ? null : entry.blocks;
        }
        budget--;

        int[][] blocks = null;
        String error;
        if (!machine.mMachine) {
            error = "not-formed";
        } else {
            blocks = runCheck(machine, te, world);
            error = blocks == null ? lastError : "";
        }

        if (blocks == null) {
            failures++;
            lastError = error;
        } else {
            captures++;
        }
        CACHE.put(key, new Entry(blocks, now));
        return blocks;
    }

    private static int[][] runCheck(MTEMultiBlockBase machine, IGregTechTileEntity te, World world) {
        synchronized (StructureCapture.class) {
            if (StructureLibAPI.isInstrumentEnabled()) {
                // 同一线程已经有别的仪表在跑，不能抢；下次再试
                lastError = "instrument-busy";
                return null;
            }

            activeBlocks.clear();
            activeWorld = world;
            active = true;

            boolean instrumented = false;
            boolean ok;
            try {
                StructureLibAPI.enableInstrument(TOKEN);
                instrumented = true;
                ok = machine.checkStructure(true, te);
            } catch (Throwable t) {
                lastError = "check:" + t.getClass().getSimpleName();
                return null;
            } finally {
                if (instrumented) {
                    try {
                        StructureLibAPI.disableInstrument();
                    } catch (Throwable ignored) {}
                }
                active = false;
                activeWorld = null;
            }

            if (!ok || activeBlocks.isEmpty()) {
                lastError = ok ? "empty" : "check-false";
                return null;
            }

            // 控制器在形状里是 skip 位，不会触发事件，单独补上
            activeBlocks.add(pack(te.getXCoord(), te.getYCoord(), te.getZCoord()));

            int[][] out = new int[activeBlocks.size()][];
            int i = 0;
            for (Long p : activeBlocks) {
                int x = (int) (p >> 42);
                int y = (int) ((p >> 21) & 0x1FFFFF);
                int z = (int) (p & 0x1FFFFF);
                // 坐标是补码存的，自行还原符号
                out[i++] = new int[] { unsign(x, 21), unsign(y, 21), unsign(z, 21) };
            }
            activeBlocks.clear();
            lastError = "";
            String cls = machine.getClass()
                .getSimpleName();
            if (LOGGED.add(cls)) {
                LOG.info("[MachineRadar] structure {} -> {} blocks/{}", cls, out.length, machine.getMetaName());
            }
            return out;
        }
    }

    private static int unsign(int v, int bits) {
        int sign = 1 << (bits - 1);
        return (v & sign) == 0 ? v : v | ~((1 << bits) - 1);
    }

    /** 三个 21 位坐标打包进 long */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFFL);
    }

    private static String key(World world, IGregTechTileEntity te) {
        return world.provider.dimensionId + ":" + te.getXCoord() + ":" + te.getYCoord() + ":" + te.getZCoord();
    }

    public static String lastError() {
        return lastError;
    }

    public static int captures() {
        return captures;
    }

    public static int failures() {
        return failures;
    }

    /** 手动清缓存（/mradar rescan 用） */
    public static void invalidate() {
        synchronized (StructureCapture.class) {
            CACHE.clear();
            budget = 8;
        }
    }

    private static final class Entry {

        final int[][] blocks;
        final long at;

        Entry(int[][] blocks, long at) {
            this.blocks = blocks;
            this.at = at;
        }
    }
}