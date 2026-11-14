package moe.takochan.machineradar.radar;

/**
 * 自检计数器：给 /machineradar status 用。
 * 三条日志/数字分别对应三个运行时风险点：
 *  - serverTicks  -> ServerTickEvent 有没有收到
 *  - lastTeSeen / lastError -> loadedTileEntityList 遍历有没有炸
 *  - lastScanNanos -> 扫描耗时
 */
public final class RadarStats {

    public static volatile long serverTicks;
    public static volatile long scans;
    public static volatile long lastScanStartMs;
    public static volatile long lastScanNanos;
    public static volatile int lastTeSeen;
    public static volatile int lastMachines;
    public static volatile String lastError;

    private RadarStats() {}
}
