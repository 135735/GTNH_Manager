package moe.takochan.machineradar.radar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import moe.takochan.machineradar.config.RadarConfig;

/**
 * 把当前快照落成 JSON，给离线分析用（config/machineradar/dump-*.json）。
 */
public final class DumpWriter {

    private static final Logger LOG = LogManager.getLogger("MachineRadar");

    private DumpWriter() {}

    public static File write() {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return null;

            File dir = new File(server.getFile("config"), "machineradar");
            if (!dir.exists() && !dir.mkdirs()) return null;

            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File out = new File(dir, "dump-" + ts + ".json");

            JsonObject root = new JsonObject();
            root.addProperty("mod", "machineradar");
            root.addProperty("version", "0.8.0");
            root.addProperty("timestamp", ts);
            root.addProperty("radius", RadarConfig.radius);
            root.addProperty("layer", RadarState.layerName(RadarState.layer));
            root.addProperty("enabled", RadarState.enabled);

            JsonObject stats = new JsonObject();
            stats.addProperty("serverTicks", RadarStats.serverTicks);
            stats.addProperty("scans", RadarStats.scans);
            stats.addProperty("lastScanMs", RadarStats.lastScanNanos / 1_000_000.0D);
            stats.addProperty("teSeen", RadarStats.lastTeSeen);
            stats.addProperty("machines", RadarStats.lastMachines);
            if (RadarStats.lastError != null) stats.addProperty("error", RadarStats.lastError);
            root.add("stats", stats);

            List<MachineRecord> snapshot = RadarState.snapshot;
            JsonArray machines = new JsonArray();
            for (int i = 0; i < snapshot.size(); i++) {
                machines.add(toJson(snapshot.get(i)));
            }
            root.add("machines", machines);

            Writer writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            try {
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(root, writer);
            } finally {
                writer.close();
            }
            return out;
        } catch (Throwable t) {
            LOG.error("[MachineRadar] dump failed", t);
            return null;
        }
    }

    private static JsonObject toJson(MachineRecord rec) {
        JsonObject o = new JsonObject();
        o.addProperty("id", rec.id);
        o.addProperty("meta", rec.metaName);
        o.addProperty("name", rec.name);
        o.addProperty("dim", rec.dim);
        o.add("pos", ints(rec.x, rec.y, rec.z));
        o.addProperty("multi", rec.multi);
        o.addProperty("assembled", rec.assembled);
        o.addProperty("allowed", rec.allowed);
        o.addProperty("status", statusName(rec.status()));
        o.addProperty("progress", rec.progress);
        o.addProperty("tier", rec.tier);
        o.addProperty("tierName", HatchRef.tierName(rec.tier));
        o.addProperty("eut", rec.eut);
        o.addProperty("maxEuInput", rec.maxEuInput);
        o.addProperty("maxAmps", rec.maxAmps);
        o.addProperty("outputName", rec.outputName);
        o.addProperty("outputAmount", rec.outputAmount);
        o.addProperty("recipeSeconds", rec.recipeSeconds());
        o.addProperty("iconName", rec.icon == null ? "" : rec.icon.getDisplayName());
        o.add("bbox", ints(rec.minX, rec.minY, rec.minZ, rec.maxX, rec.maxY, rec.maxZ));
        o.addProperty("bboxExact", rec.bboxExact);
        o.addProperty("blockCount", rec.blockCount);
        o.addProperty("bboxError", rec.bboxError);
        o.addProperty("structureError", rec.bboxExact ? "" : rec.bboxError);

        JsonObject counts = new JsonObject();
        for (int kind = 0; kind <= HatchRef.OTHER; kind++) {
            int n = rec.hatchCount(kind);
            if (n > 0) counts.addProperty(HatchRef.keyOf(kind), n);
        }
        o.add("hatchCounts", counts);

        JsonArray list = new JsonArray();
        for (int i = 0; i < rec.hatches.length; i++) {
            HatchRef h = rec.hatches[i];
            JsonObject ho = new JsonObject();
            ho.addProperty("kind", HatchRef.keyOf(h.kind));
            ho.addProperty("tier", h.tier);
            ho.add("pos", ints(h.x, h.y, h.z));
            ho.addProperty("amount", h.amount);
            ho.addProperty("capacity", h.capacity);
            ho.addProperty("content", h.content);
            ho.addProperty("usedSlots", h.usedSlots);
            ho.addProperty("totalSlots", h.totalSlots);
            ho.addProperty("facing", h.facing);
            ho.addProperty("facingName", HatchRef.facingName(h.facing));
            ho.addProperty("maxEu", h.maxEu);
            ho.addProperty("amps", h.amps);
            list.add(ho);
        }
        o.add("hatches", list);
        o.addProperty("hatchTotal", rec.hatches.length);
        return o;
    }

    private static JsonArray ints(int... values) {
        JsonArray a = new JsonArray();
        for (int v : values) a.add(new JsonPrimitive(Integer.valueOf(v)));
        return a;
    }

    private static String statusName(byte status) {
        switch (status) {
            case MachineRecord.RUNNING:
                return "running";
            case MachineRecord.IDLE:
                return "idle";
            case MachineRecord.UNFORMED:
                return "unformed";
            default:
                return "single";
        }
    }
}