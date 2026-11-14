package moe.takochan.machineradar.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import moe.takochan.machineradar.client.MachineGroups;
import moe.takochan.machineradar.client.RadarText;
import moe.takochan.machineradar.radar.MachineGroup;
import moe.takochan.machineradar.radar.MachineRecord;
import moe.takochan.machineradar.radar.RadarState;

/**
 * 「这批机器归到哪个分组」的选择器：列表里的「加入组…」和世界里的「选区归组」共用。
 *
 * <p>以前必须先到左栏选一个分组、再点加入；现在直接在这里点「＋ 新建分组」或任一已有分组，
 * 一步完成。如果是从 3D 选区进来的，还能顺手把选区记成该分组的**显式区域**
 * （分组范围不再依赖成员机器的包围盒）。
 */
public class GuiGroupAssign extends GuiScreen {

    private static final int PAD = 12;
    private static final int ROW_H = 20;
    private static final int HEADER_H = 58;
    private static final int FOOTER_H = 34;

    private final List<MachineRecord> machines = new ArrayList<MachineRecord>();
    private final boolean fromRegion;
    private final boolean returnToList;
    private final int dim;
    private final int[] region;

    private boolean useRegion;
    private boolean replaceMode;
    private int scroll;
    private int listX, listY, listW, listH;
    private int panelX, panelY, panelW, panelH;

    /**
     * @param machines     要归组的机器（可以为空，空 = 只把区域记到分组上）
     * @param fromRegion   true = 从 3D 选区进来（归完留在世界里，不清选区）
     * @param returnToList 归完是否回到工厂列表（列表里点「加入组…」时为 true）
     * @param dim          维度
     * @param region       可选的选区 {minX..maxZ}；null = 没有选区
     */
    public GuiGroupAssign(List<MachineRecord> machines, boolean fromRegion, boolean returnToList, int dim,
        int[] region) {
        if (machines != null) this.machines.addAll(machines);
        this.fromRegion = fromRegion;
        this.returnToList = returnToList;
        this.dim = dim;
        this.region = region == null || region.length != 6 ? null
            : new int[] { region[0], region[1], region[2], region[3], region[4], region[5] };
        this.useRegion = fromRegion && this.region != null;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        MachineGroups.ensureLoaded();

        panelW = Math.min(width - 40, 470);
        panelH = Math.min(height - 40, 360);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;

        listX = panelX + PAD;
        listY = panelY + HEADER_H;
        listW = panelW - PAD * 2;
        listH = panelH - HEADER_H - FOOTER_H;

        int by = panelY + panelH - FOOTER_H + 7;
        int toggleX = listX;
        if (region != null) {
            buttonList.add(new GuiButton(901, toggleX, panelY + 34, 190, 18, regionToggleLabel()));
            toggleX += 196;
        }
        buttonList.add(new GuiButton(902, toggleX, panelY + 34, 150, 18, modeLabel()));
        buttonList.add(new GuiButton(900, panelX + panelW - PAD - 70, by, 70, 20, "取消"));

        scroll = 0;
        clampScroll();
    }

    private String modeLabel() {
        return replaceMode ? "模式：替换成员" : "模式：加入成员";
    }

    private String regionToggleLabel() {
        return "记录为分组区域：" + (useRegion ? "开" : "关");
    }

    private int rowCount() {
        return 1 + MachineGroups.GROUPS.size();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, 0xC0000000);
        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0xE8101418);
        drawRect(panelX, panelY, panelX + panelW, panelY + 1, 0xFF3A6EA5);

        String title = fromRegion ? "选区归组" : "加入分组";
        drawCenteredString(fontRendererObj, "Machine Radar · " + title, width / 2, panelY + 8, 0xFFFFFF);

        StringBuilder sub = new StringBuilder();
        sub.append(machines.size())
            .append(" 台机器  ·  维度 ")
            .append(dim)
            .append(replaceMode ? "  ·  替换已有组的全部成员" : "  ·  加入（保留原有成员）");
        if (region != null) {
            int dx = region[3] - region[0] + 1;
            int dy = region[4] - region[1] + 1;
            int dz = region[5] - region[2] + 1;
            sub.append("  ·  选区 ")
                .append(dx)
                .append("×")
                .append(dy)
                .append("×")
                .append(dz);
        }
        drawCenteredString(
            fontRendererObj,
            RadarText.trim(fontRendererObj, sub.toString(), panelW - PAD * 2),
            width / 2,
            panelY + 22,
            0x90A4AE);

        drawRect(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, 0x40000000);
        drawGroupList(mouseX, mouseY);

        if (listH < rowCount() * ROW_H) {
            drawScrollHint();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawGroupList(int mouseX, int mouseY) {
        RenderHelper.enableGUIStandardItemLighting();
        for (int i = 0; i < rowCount(); i++) {
            int y = listY + i * ROW_H - scroll;
            if (y + ROW_H < listY || y > listY + listH) continue;
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H - 1;
            drawRect(listX, y, listX + listW, y + ROW_H - 1, hover ? 0x503F6FA5 : 0x18FFFFFF);

            if (i == 0) {
                drawRect(listX + 2, y + 4, listX + 5, y + ROW_H - 5, 0xFF66BB6A);
                String label = machines.isEmpty() ? "＋ 新建分组（只记区域）" : "＋ 新建分组（" + machines.size() + " 台）";
                int labelW = fontRendererObj.getStringWidth(label);
                if (labelW > listW - 90) label = RadarText.trim(fontRendererObj, label, listW - 90);
                fontRendererObj.drawStringWithShadow(label, listX + 9, y + 6, 0xFFFFFF);

                String hint = useRegion && region != null ? "含区域" : "";
                int hintW = fontRendererObj.getStringWidth(hint);
                if (!hint.isEmpty()) {
                    fontRendererObj.drawStringWithShadow(
                        hint,
                        listX + listW - 4 - hintW,
                        y + 6,
                        0x81C784);
                }
                continue;
            }

            MachineGroup group = MachineGroups.GROUPS.get(i - 1);
            drawRect(listX + 2, y + 4, listX + 5, y + ROW_H - 5, 0xFF000000 | group.color);

            int textX = listX + 9;
            ItemStack icon = group.iconStack();
            if (icon != null) {
                RenderItem.getInstance()
                    .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, listX + 3, y + 1);
                textX = listX + 21;
            }

            String count = "在线 " + MachineGroups.onlineCount(group, dim) + " / 共 " + group.size();
            int countW = fontRendererObj.getStringWidth(count);
            String add = replaceMode ? "替换" : "+" + machines.size();
            int addW = fontRendererObj.getStringWidth(add);
            drawString(
                fontRendererObj,
                add,
                listX + listW - 6 - addW,
                y + 6,
                replaceMode ? 0xFFB74D : (machines.isEmpty() ? 0x546E7A : 0x81C784));
            fontRendererObj.drawStringWithShadow(count, listX + listW - 12 - addW - countW, y + 6, 0x90A4AE);

            String name = RadarText.trim(
                fontRendererObj,
                group.name + (group.hasRegion(dim) ? "  ⬚" + group.regionSizeText() : ""),
                Math.max(20, listX + listW - 18 - addW - countW - textX));
            fontRendererObj.drawStringWithShadow(name, textX, y + 6, 0xE0E0E0);
        }
        RenderHelper.disableStandardItemLighting();
    }

    private void drawScrollHint() {
        drawString(fontRendererObj, "滚轮翻页", listX, listY + listH + 3, 0x546E7A);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        scroll -= wheel / 120 * ROW_H;
        clampScroll();
    }

    private void clampScroll() {
        int max = Math.max(0, rowCount() * ROW_H - listH);
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) return;

        int index = (mouseY - listY + scroll) / ROW_H;
        if (index < 0 || index >= rowCount()) return;
        apply(index == 0, index == 0 ? null : MachineGroups.GROUPS.get(index - 1));
    }

    private void apply(boolean createNew, MachineGroup group) {
        int n = machines.size();
        if (createNew) {
            group = MachineGroups.createWith(machines, dim, useRegion ? region : null);
            say("已新建 " + EnumChatFormatting.WHITE + group.name + EnumChatFormatting.RESET + "（" + n + " 台"
                + (useRegion && region != null ? "，含区域" : "") + "）");
        } else {
            if (group == null) return;
            if (replaceMode) {
                MachineGroups.replaceAll(machines, group);
            } else {
                MachineGroups.assignAll(machines, group);
            }
            if (useRegion && region != null) MachineGroups.setRegion(group, dim, region);
            say(
                (replaceMode ? "已替换 " : "已加入 ") + EnumChatFormatting.WHITE
                    + group.name
                    + EnumChatFormatting.RESET
                    + "："
                    + n
                    + " 台"
                    + (useRegion && region != null ? "，区域已更新" : ""));
        }
        RadarState.enabled = true;
        back();
    }

    private void say(String msg) {
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[MachineRadar] " + msg));
        }
    }

    private void back() {
        if (returnToList) mc.displayGuiScreen(new GuiMachineList());
        else mc.displayGuiScreen(null);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            back();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 900) {
            back();
            return;
        }
        if (button.id == 901) {
            useRegion = !useRegion;
            button.displayString = regionToggleLabel();
            return;
        }
        if (button.id == 902) {
            replaceMode = !replaceMode;
            button.displayString = modeLabel();
        }
    }
}
