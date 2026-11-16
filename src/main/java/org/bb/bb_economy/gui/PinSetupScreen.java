package org.bb.bb_economy.gui;

import org.bb.bb_economy.Msg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.network.SetCardPinPacket;

import java.util.List;

public class PinSetupScreen extends Screen {

    private EditBox pinField;
    private EditBox confirmPinField;
    private Component message = Component.translatable(Msg.PREFIX + "gui.choose_pin");
    private int messageColor = 0xFFFFFF;
    private boolean submitting;

    public PinSetupScreen() {
        super(Component.translatable(Msg.PREFIX + "gui.pin_setup_title"));
    }

    @Override
    protected void init() {
        super.init();
        int cx = this.width / 2;
        int cy = this.height / 2 - 34;
        int width = 132;

        pinField = new EditBox(this.font, cx - width / 2, cy, width, 18, Component.translatable(Msg.PREFIX + "gui.pin"));
        pinField.setHint(Component.translatable(Msg.PREFIX + "gui.new_pin"));
        pinField.setFilter(value -> value.matches("\\d{0,4}"));
        this.addRenderableWidget(pinField);

        confirmPinField = new EditBox(this.font, cx - width / 2, cy + 26, width, 18, Component.translatable(Msg.PREFIX + "gui.confirmation"));
        confirmPinField.setHint(Component.translatable(Msg.PREFIX + "gui.confirmation"));
        confirmPinField.setFilter(value -> value.matches("\\d{0,4}"));
        this.addRenderableWidget(confirmPinField);

        this.addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.confirm"), btn -> submit())
                .pos(cx - width / 2, cy + 58).size(width, 20).build());

        setInitialFocus(pinField);
    }

    private void submit() {
        if (submitting) return;

        String pin = pinField.getValue().trim();
        String confirmation = confirmPinField.getValue().trim();

        if (!pin.matches("\\d{4}")) {
            setMessage(Msg.of(Msg.PREFIX + "pin.four_digits"), 0xFF4444);
            return;
        }
        if (!pin.equals(confirmation)) {
            setMessage(Msg.of(Msg.PREFIX + "gui.pin_mismatch"), 0xFF4444);
            return;
        }

        submitting = true;
        setMessage(Msg.of(Msg.PREFIX + "gui.saving"), 0xAAAAAA);
        ModNetworking.CHANNEL.sendToServer(new SetCardPinPacket(pin));
    }

    public void onPinSaved(boolean success, String responseMessage) {
        submitting = false;
        setMessage(responseMessage, success ? 0x44FF44 : 0xFF4444);
        if (success && this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    private void setMessage(String text, int color) {
        this.message = Msg.component(text);
        this.messageColor = color;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        int cx = this.width / 2;
        int cy = this.height / 2;
        int w = 220;
        int h = 210;
        int x = cx - w / 2;
        int y = cy - h / 2;

        g.fill(x, y, x + w, y + h, 0xB0101010);
        g.fill(x, y, x + w, y + 1, 0xFF505050);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF505050);
        g.fill(x, y, x + 1, y + h, 0xFF505050);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF505050);

        String title = Msg.tr("gui.new_pin");
        g.drawString(this.font, title, cx - this.font.width(title) / 2, y + 12, 0xEAEAEA, false);

        super.render(g, mouseX, mouseY, partialTick);

        List<FormattedCharSequence> lines = this.font.split(message, 176);
        int lineY = y + 172;
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, cx - 88, lineY, messageColor, false);
            lineY += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
