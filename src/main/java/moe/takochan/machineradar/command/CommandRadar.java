package moe.takochan.machineradar.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import moe.takochan.machineradar.MachineRadar;
import moe.takochan.machineradar.radar.DumpWriter;
import moe.takochan.machineradar.radar.RadarScanner;
import moe.takochan.machineradar.radar.RadarState;
import moe.takochan.machineradar.radar.RadarStats;
import moe.takochan.machineradar.radar.StructureCapture;

/**
 * /machineradar [status|on|off|scan]
 * 纯服务端命令：就算渲染或事件注册挂了，这条命令也能告诉你挂在哪一环。
 */
public class CommandRadar extends CommandBase {

    private static final String HEAD = EnumChatFormatting.AQUA + "[MR] " + EnumChatFormatting.RESET;

    @Override
    public String getCommandName() {
        return "machineradar";
    }

    @Override
    public List getCommandAliases() {
        return Arrays.asList("mradar");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/machineradar [status|on|off|scan|rescan|dump|list]";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ENGLISH) : "status";

        if ("on".equals(sub)) {
            RadarState.enabled = true;
            reply(sender, EnumChatFormatting.GREEN + "radar ON");
            return;
        }
        if ("off".equals(sub)) {
            RadarState.enabled = false;
            reply(sender, EnumChatFormatting.GRAY + "radar OFF");
            return;
        }
        if ("rescan".equals(sub)) {
            StructureCapture.invalidate();
            RadarScanner.scanNow();
            reply(sender, "structure cache cleared; scan " + RadarState.snapshot.size() + " machines");
            return;
        }
        if ("scan".equals(sub)) {
            RadarScanner.scanNow();
            reply(
                sender,
                "forced scan: " + String.format(Locale.ROOT, "%.2f ms", RadarStats.lastScanNanos / 1_000_000.0D)
                    + "  te="
                    + RadarStats.lastTeSeen
                    + "  machines="
                    + RadarStats.lastMachines);
            replyError(sender);
            return;
        }

        if ("list".equals(sub) || "gui".equals(sub)) {
            // 单机里 integrated server 与客户端同进程：转到客户端线程开界面
            MachineRadar.proxy.openMachineList();
            reply(sender, "opening machine list…");
            return;
        }

        if ("dump".equals(sub)) {
            java.io.File f = DumpWriter.write();
            if (f == null) {
                reply(sender, EnumChatFormatting.RED + "dump failed, see log");
            } else {
                reply(sender, "dumped " + RadarState.snapshot.size() + " machines -> " + f.getAbsolutePath());
            }
            return;
        }

        // status
        reply(sender, "radar=" + (RadarState.enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.GRAY + "OFF")
            + EnumChatFormatting.RESET
            + "  serverTicks="
            + RadarStats.serverTicks
            + "  scans="
            + RadarStats.scans);
        reply(
            sender,
            "last=" + String.format(Locale.ROOT, "%.2f ms", RadarStats.lastScanNanos / 1_000_000.0D)
                + "  te="
                + RadarStats.lastTeSeen
                + "  machines="
                + RadarStats.lastMachines
                + "  snapshot="
                + RadarState.snapshot.size()
                + "  struct="
                + StructureCapture.captures()
                + "/"
                + (StructureCapture.captures() + StructureCapture.failures()));
        replyError(sender);
    }

    private void replyError(ICommandSender sender) {
        String err = RadarStats.lastError;
        if (err == null) {
            reply(sender, EnumChatFormatting.DARK_GREEN + "error: none");
        } else {
            reply(sender, EnumChatFormatting.RED + "error: " + err);
        }
    }

    private void reply(ICommandSender sender, String msg) {
        sender.addChatMessage(new ChatComponentText(HEAD + msg));
    }
}