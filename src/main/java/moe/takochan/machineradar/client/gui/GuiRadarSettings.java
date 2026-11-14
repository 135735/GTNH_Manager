package moe.takochan.machineradar.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Mouse;

import moe.takochan.machineradar.config.RadarConfig;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 游戏内设置界面（默认 M 打开）。
 * 所有开关/滑条即时生效，"完成"时写回 config/machineradar.cfg。
 */
public class GuiRadarSettings extends GuiScreen {

    private static int nextId = 100;
    private final List<RadarToggle> toggles = new ArrayList<RadarToggle>();
    private final List<RadarSlider> sliders = new ArrayList<RadarSlider>();

    @Override
    public void initGui() {
        buttonList.clear();
        toggles.clear();
        sliders.clear();

        int cx = width / 2;
        int leftX = cx - 212;
        int rightX = cx + 12;
        int top = 42;
        int w = 200;
        int h = 20;
        int gap = 3;

        int y = top;
        addToggle(leftX, y, w, h, "showFills", "外壳填色");
        y += h + gap;
        addToggle(leftX, y, w, h, "showWireframe", "线框轮廓");
        y += h + gap;
        addToggle(leftX, y, w, h, "showIcons", "机器图标");
        y += h + gap;
        addToggle(leftX, y, w, h, "showLabels", "机器名字");
        y += h + gap;
        addToggle(leftX, y, w, h, "showHatches", "显示舱室");
        y += h + gap;
        addToggle(leftX, y, w, h, "hatchFocusOnly", "只显示焦点舱室");
        y += h + gap;
        addToggle(leftX, y, w, h, "showHatchFacing", "舱室朝向");
        y += h + gap;
        addToggle(leftX, y, w, h, "overlayXray", "外壳 X 光");
        y += h + gap;
        addToggle(leftX, y, w, h, "wireframeFocusOnly", "线框只画点选机器");
        y += h + gap;
        addToggle(leftX, y, w, h, "wireframeXray", "线框 X 光");

        y = top;
        addSlider(rightX, y, w, h, "fillRadius", "填色距离", 8, 128);
        y += h + gap;
        addSlider(rightX, y, w, h, "wireframeRadius", "线框距离", 16, 256);
        y += h + gap;
        addSlider(rightX, y, w, h, "iconRadius", "图标距离", 8, 128);
        y += h + gap;
        addSlider(rightX, y, w, h, "labelRadius", "名字距离", 8, 128);
        y += h + gap;
        addSlider(rightX, y, w, h, "hatchNearRadius", "舱室距离", 2, 64);
        y += h + gap;
        addSlider(rightX, y, w, h, "overlayAlpha", "填色透明度", 5, 255);
        y += h + gap;
        addSlider(rightX, y, w, h, "outlineAlpha", "线框透明度", 10, 255);
        y += h + gap;
        addSlider(rightX, y, w, h, "maxLabels", "名字数量", 4, 120);
        y += h + gap;
        addSlider(rightX, y, w, h, "secondaryConeDegrees", "副焦点角度", 0, 15);
        y += h + gap;
        addSlider(rightX, y, w, h, "wireframeMaxMachines", "线框机器数", 0, 256);

        int bottom = top + (h + gap) * 10 + 14;
        buttonList.add(new GuiButton(900, cx - 154, bottom, 100, 20, "完成并保存"));
        buttonList.add(new GuiButton(901, cx + 54, bottom, 100, 20, "取消"));
        buttonList.add(new GuiButton(902, cx - 50, bottom, 100, 20, layerLabel()));
        buttonList.add(new GuiButton(903, cx - 50, bottom + 24, 100, 20, "工厂列表 (L)"));
    }

    private void addToggle(int x, int y, int w, int h, String field, String label) {
        RadarToggle b = new RadarToggle(x, y, w, h, field, label);
        toggles.add(b);
        buttonList.add(b);
    }

    private void addSlider(int x, int y, int w, int h, String field, String label, int min, int max) {
        RadarSlider s = new RadarSlider(x, y, w, h, field, label, min, max);
        sliders.add(s);
        buttonList.add(s);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "Machine Radar 设置", width / 2, 16, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            "修改即时生效，点「完成并保存」写入 config/machineradar.cfg",
            width / 2,
            28,
            0x9E9E9E);

        super.drawScreen(mouseX, mouseY, partialTicks);

        drawCenteredString(
            fontRendererObj,
            "K 开关雷达   Ctrl+K 图层   Shift+K 导出   M 本界面   L 工厂列表",
            width / 2,
            height - 20,
            0x607D8B);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 900) {
            RadarConfig.save();
            mc.displayGuiScreen(null);
            return;
        }
        if (button.id == 901) {
            mc.displayGuiScreen(null);
            return;
        }
        if (button.id == 902) {
            RadarState.layer = (RadarState.layer + 1) % 3;
            button.displayString = layerLabel();
            return;
        }
        if (button.id == 903) {
            RadarConfig.save();
            mc.displayGuiScreen(new GuiMachineList());
        }
    }

    private static String layerLabel() {
        return "图层：" + RadarState.layerName(RadarState.layer);
    }

    // ------------------------------------------------------------------ widgets

    static class RadarToggle extends GuiButton {

        final String field;
        final String label;

        RadarToggle(int x, int y, int w, int h, String field, String label) {
            super(nextId++, x, y, w, h, label);
            this.field = field;
            this.label = label;
        }

        boolean value() {
            return RadarConfig.reflectGetBool(field);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;
            boolean on = value();
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, hover ? 0xB0243B4A : 0x90101418);
            drawRect(xPosition, yPosition, xPosition + 3, yPosition + height, on ? 0xFF4CAF50 : 0xFF555555);
            drawString(mc.fontRenderer, label, xPosition + 9, yPosition + 6, 0xE0E0E0);
            String state = on ? "开" : "关";
            drawString(
                mc.fontRenderer,
                state,
                xPosition + width - 8 - mc.fontRenderer.getStringWidth(state),
                yPosition + 6,
                on ? 0x66FF66 : 0x9E9E9E);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!super.mousePressed(mc, mouseX, mouseY)) return false;
            RadarConfig.reflectSetBool(field, !value());
            return true;
        }
    }

    static class RadarSlider extends GuiButton {

        final String field;
        final String label;
        final int min, max;
        boolean dragging;

        RadarSlider(int x, int y, int w, int h, String field, String label, int min, int max) {
            super(field.hashCode() & 0x7FFF, x, y, w, h, label);
            this.field = field;
            this.label = label;
            this.min = min;
            this.max = max;
        }

        int value() {
            int v = RadarConfig.reflectGetInt(field);
            return v < min ? min : (v > max ? max : v);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            int v = value();
            float p = (float) (v - min) / (float) Math.max(1, max - min);
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0x90101418);
            drawRect(xPosition + 2, yPosition + 2, xPosition + 2 + (int) ((width - 4) * p), yPosition + height - 2, 0x80449AE0);
            drawString(mc.fontRenderer, label, xPosition + 7, yPosition + 6, 0xE0E0E0);
            String val = String.valueOf(v);
            drawString(
                mc.fontRenderer,
                val,
                xPosition + width - 7 - mc.fontRenderer.getStringWidth(val),
                yPosition + 6,
                0xFFFFFF);
        }

        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            if (!enabled || !visible) return false;
            if (mouseX < xPosition || mouseY < yPosition || mouseX >= xPosition + width || mouseY >= yPosition + height)
                return false;
            dragging = true;
            update(mouseX);
            return true;
        }

        @Override
        public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
            if (dragging) update(mouseX);
        }

        @Override
        public void mouseReleased(int mouseX, int mouseY) {
            dragging = false;
        }

        private void update(int mouseX) {
            float p = (float) (mouseX - xPosition - 2) / (float) Math.max(1, width - 4);
            p = p < 0 ? 0 : (p > 1 ? 1 : p);
            int v = min + Math.round(p * (max - min));
            RadarConfig.reflectSetInt(field, v);
        }
    }
}