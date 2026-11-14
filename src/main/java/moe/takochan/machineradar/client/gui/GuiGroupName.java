package moe.takochan.machineradar.client.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;

import org.lwjgl.input.Keyboard;

import moe.takochan.machineradar.client.MachineGroups;
import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RegionSelection;

/**
 * 分组设置小窗口（列表左栏右键分组打开）：改名、删除、用当前选中机器的图标、
 * 把世界里已完成的 3D 选区记成这个分组的显式区域（或清除区域）。
 */
public class GuiGroupName extends GuiScreen {

    private final MachineGroup group;
    private final MachineRecord selected;
    private GuiTextField field;
    private String status = "";

    public GuiGroupName(MachineGroup group, MachineRecord selected) {
        this.group = group;
        this.selected = selected;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int cx = width / 2;
        field = new GuiTextField(fontRendererObj, cx - 80, height / 2 - 16, 200, 20);
        field.setMaxStringLength(32);
        field.setText(group == null ? "" : group.name);
        field.setFocused(true);

        buttonList.add(new GuiButton(900, cx - 150, height / 2 + 14, 90, 20, "保存"));
        buttonList.add(new GuiButton(901, cx - 45, height / 2 + 14, 90, 20, "删除分组"));
        buttonList.add(new GuiButton(902, cx + 60, height / 2 + 14, 90, 20, "取消"));

        int y = height / 2 + 40;
        int x = cx - 186;
        buttonList.add(new GuiButton(903, x, y, 150, 20, "用选中机器图标"));
        buttonList.add(new GuiButton(904, x + 156, y, 124, 20, "设为当前选区"));
        buttonList.add(new GuiButton(905, x + 286, y, 86, 20, "清除区域"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (mc == null || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "分组设置", width / 2, height / 2 - 62, 0xFFFFFF);

        String count = group == null ? "?" : "当前 " + group.size() + " 台机器";
        if (group != null) {
            count += group.hasRegion(mc.thePlayer.dimension) ? "  ·  区域 " + group.regionSizeText()
                : (group.hasRegion() ? "  ·  区域在维度 " + group.regionDim : "  ·  无显式区域");
        }
        drawCenteredString(fontRendererObj, count, width / 2, height / 2 - 50, 0x9E9E9E);

        ItemStack icon = group == null ? null : group.iconStack();
        if (icon != null) {
            RenderHelper.enableGUIStandardItemLighting();
            RenderItem.getInstance()
                .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, width / 2 - 108, height / 2 - 14);
            RenderHelper.disableStandardItemLighting();
        }

        if (field != null) field.drawTextBox();

        String hint;
        if (!status.isEmpty()) {
            hint = status;
        } else if (RegionSelection.isComplete()) {
            hint = "世界里已有选区 " + RegionSelection.sizeText() + "（维度 " + RegionSelection.dim() + "）";
        } else if (selected != null) {
            hint = "选中机器：" + selected.name + "（可用它的图标）";
        } else {
            hint = "先在列表里选中机器再用图标；区域用世界里的 3D 选区";
        }
        drawCenteredString(
            fontRendererObj,
            trim(hint, width - 40),
            width / 2,
            height / 2 + 68,
            status.isEmpty() ? 0x607D8B : 0x81C784);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private String trim(String s, int maxWidth) {
        if (fontRendererObj.getStringWidth(s) <= maxWidth) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (fontRendererObj.getStringWidth(sb.toString() + s.charAt(i) + "…") > maxWidth) break;
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    @Override
    public void updateScreen() {
        if (field != null) field.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (field != null && field.isFocused() && field.textboxKeyTyped(typedChar, keyCode)) return;
        if (keyCode == Keyboard.KEY_ESCAPE) {
            back();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            save();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (field != null) field.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 900) {
            save();
            return;
        }
        if (button.id == 901) {
            if (group != null) MachineGroups.delete(group);
            back();
            return;
        }
        if (button.id == 902) {
            back();
            return;
        }
        if (button.id == 903) {
            if (group != null && selected != null && selected.icon != null) {
                group.setIcon(selected.icon);
                MachineGroups.save();
                status = "图标已更新";
            } else {
                status = "没有可用的机器图标";
            }
            return;
        }
        if (button.id == 904) {
            if (group == null) return;
            if (!RegionSelection.isComplete()) {
                status = "世界里没有完成的选区：按 V 框两个角点";
                return;
            }
            MachineGroups.setRegion(group, RegionSelection.dim(), RegionSelection.box());
            status = "已记为该分组区域 " + group.regionSizeText();
            return;
        }
        if (button.id == 905) {
            if (group == null) return;
            MachineGroups.clearRegion(group);
            status = "已清除分组区域（预览回到成员包围盒）";
        }
    }

    private void save() {
        if (group != null && field != null) MachineGroups.rename(group, field.getText());
        back();
    }

    private void back() {
        mc.displayGuiScreen(new GuiMachineList());
    }
}
