package moe.takochan.machineradar.client;

import java.util.Locale;

import net.minecraft.client.gui.FontRenderer;

/**
 * HUD 与列表界面共用的数字/文字格式化。
 */
public final class RadarText {

    private RadarText() {}

    /** 12345 -> "12.3k" */
    public static String fmt(long v) {
        return num(v);
    }

    /** 12345 -> "12.3k"；1000000 -> "1.0M" */
    public static String num(long v) {
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0D);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.1fk", v / 1_000.0D);
        return Long.toString(v);
    }

    /** 1234567 -> "1.2M EU" */
    public static String eu(long v) {
        if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM EU", v / 1_000_000.0D);
        if (v >= 1_000L) return String.format(Locale.ROOT, "%.0fk EU", v / 1_000.0D);
        return v + " EU";
    }

    /** 按像素宽度截断，超出加省略号 */
    public static String trim(FontRenderer fr, String s, int maxWidth) {
        if (s == null) return "";
        if (fr.getStringWidth(s) <= maxWidth) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (fr.getStringWidth(sb.toString() + s.charAt(i) + "…") > maxWidth) break;
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    /** 距离（格），四舍五入 */
    public static String dist(double x, double y, double z, double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return ((int) Math.sqrt(dx * dx + dy * dy + dz * dz)) + "m";
    }
}
