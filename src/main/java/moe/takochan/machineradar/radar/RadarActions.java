package moe.takochan.machineradar.radar;

import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 客户端 → 服务端线程的动作队列（单机 integrated server 与客户端同进程）。
 *
 * <p>GUI 在客户端线程把动作丢进来，{@link RadarScanner} 每个 ServerTick 取出执行。
 * 多人环境下客户端没有服务端队列，动作会被丢弃（本 mod 目前只支持单机）。
 */
public final class RadarActions {

    private static final Logger LOG = LogManager.getLogger("MachineRadar");
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<Runnable>();

    private RadarActions() {}

    public static void submit(Runnable task) {
        if (task != null) QUEUE.add(task);
    }

    public static int pending() {
        return QUEUE.size();
    }

    /** 在服务端 tick 里调用 */
    public static void process() {
        Runnable task;
        while ((task = QUEUE.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                LOG.warn("[MachineRadar] remote action failed: {}", t.toString());
            }
        }
    }
}
