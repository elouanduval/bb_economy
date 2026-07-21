package org.bb.bb_economy.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.network.AtmActionPacket;
import org.bb.bb_economy.network.PinCheckPacket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class AtmScreen extends Screen implements MenuAccess<AtmScreenHandler> {

    private static final String PIN_HINT = "Entrez votre PIN";

    private final AtmScreenHandler handler;

    private enum AtmState { PIN, MAIN_MENU, DEPOSIT, WITHDRAW, TRANSFER }

    private AtmState state = AtmState.PIN;
    private final StringBuilder pinInput = new StringBuilder();
    private BigDecimal currentBalance = BigDecimal.ZERO;
    private Component message = Component.literal(PIN_HINT);
    private int messageColorInt = 0xFFFFFF;

    private final List<Button> pinButtons = new ArrayList<>();
    private EditBox amountField;
    private EditBox transferTargetField;
    private boolean transferFieldHasPrefix;

    public AtmScreen(AtmScreenHandler handler, Inventory inv, Component title) {
        super(title);
        this.handler = handler;
    }

    @Override
    public AtmScreenHandler getMenu() {
        return this.handler;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        pinButtons.clear();
        amountField = null;
        transferTargetField = null;
        transferFieldHasPrefix = false;

        switch (state) {
            case PIN -> buildPinScreen();
            case MAIN_MENU -> buildMainMenu();
            case DEPOSIT -> buildAmountScreen("Depot", false);
            case WITHDRAW -> buildAmountScreen("Retrait", false);
            case TRANSFER -> buildAmountScreen("Virement", true);
        }
    }

    private void buildPinScreen() {
        int btnSize = 32;
        int pad = 6;
        int startX = this.width / 2 - (btnSize * 3 + pad * 2) / 2;
        int startY = this.height / 2 - 18;

        for (int i = 1; i <= 9; i++) {
            int col = (i - 1) % 3;
            int row = (i - 1) / 3;
            int x = startX + col * (btnSize + pad);
            int y = startY + row * (btnSize + pad);
            int num = i;
            Button button = Button.builder(Component.literal(String.valueOf(i)), btn -> appendPin(num))
                    .pos(x, y).size(btnSize, btnSize).build();
            pinButtons.add(button);
            addRenderableWidget(button);
        }

        Button delete = Button.builder(Component.literal("Eff"), btn -> deletePin())
                .pos(startX, startY + 3 * (btnSize + pad)).size(btnSize, btnSize).build();
        pinButtons.add(delete);
        addRenderableWidget(delete);

        Button zero = Button.builder(Component.literal("0"), btn -> appendPin(0))
                .pos(startX + btnSize + pad, startY + 3 * (btnSize + pad)).size(btnSize, btnSize).build();
        pinButtons.add(zero);
        addRenderableWidget(zero);

        Button close = Button.builder(Component.literal("Fer"), btn -> onClose())
                .pos(startX + (btnSize + pad) * 2, startY + 3 * (btnSize + pad)).size(btnSize, btnSize).build();
        pinButtons.add(close);
        addRenderableWidget(close);
    }

    private void buildMainMenu() {
        int cx = this.width / 2;
        int cy = this.height / 2 - 22;
        int width = 120;
        addRenderableWidget(Button.builder(Component.literal("Depot"), btn -> { state = AtmState.DEPOSIT; rebuildScreen(); })
                .pos(cx - width / 2, cy + 2).size(width, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Retrait"), btn -> { state = AtmState.WITHDRAW; rebuildScreen(); })
                .pos(cx - width / 2, cy + 28).size(width, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Virement"), btn -> { state = AtmState.TRANSFER; rebuildScreen(); })
                .pos(cx - width / 2, cy + 54).size(width, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Fermer"), btn -> onClose())
                .pos(cx - width / 2, cy + 92).size(width, 20).build());
    }

    private void buildAmountScreen(String action, boolean transfer) {
        int cx = this.width / 2;
        int cy = this.height / 2 - (transfer ? 30 : 12);
        int width = 132;

        if (transfer) {
            int prefixWidth = this.font.width("ACC-") + 8;
            transferTargetField = new EditBox(this.font, cx - width / 2 + prefixWidth, cy, width - prefixWidth, 18, Component.literal("Compte"));
            transferTargetField.setHint(Component.literal("Numero"));
            transferFieldHasPrefix = true;
            addRenderableWidget(transferTargetField);
            cy += 28;
        }

        amountField = new EditBox(this.font, cx - width / 2, cy, width, 18, Component.literal("Montant"));
        amountField.setHint(Component.literal("Montant"));
        addRenderableWidget(amountField);

        addRenderableWidget(Button.builder(Component.literal(action), btn -> {
            switch (state) {
                case DEPOSIT -> doDeposit();
                case WITHDRAW -> doWithdraw();
                case TRANSFER -> doTransfer();
                default -> { }
            }
        }).pos(cx - width / 2, cy + 28).size(width, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Retour"), btn -> goToMainMenu())
                .pos(cx - width / 2, cy + 54).size(width, 20).build());
    }

    private void appendPin(int num) {
        if (pinInput.length() < 4) {
            pinInput.append(num);
            setMessage(PIN_HINT, 0xFFFFFF);
            if (pinInput.length() == 4) sendPinToServer();
        }
    }

    private void deletePin() {
        if (pinInput.length() > 0) pinInput.deleteCharAt(pinInput.length() - 1);
    }

    private void sendPinToServer() {
        int pin = Integer.parseInt(pinInput.toString());
        ModNetworking.CHANNEL.sendToServer(new PinCheckPacket(pin));
        setMessage("Verification...", 0xAAAAAA);
    }

    public void onPinResponse(boolean success, double balance) {
        if (success) {
            currentBalance = BigDecimal.valueOf(balance);
            state = AtmState.MAIN_MENU;
            setMessage("Solde : " + currentBalance + " EUR", 0xFFFFFF);
            rebuildScreen();
        } else {
            setMessage("PIN incorrect", 0xFF4444);
            pinInput.setLength(0);
        }
    }

    private void doDeposit() {
        BigDecimal amount = parseAmount();
        if (amount == null) return;
        ModNetworking.CHANNEL.sendToServer(new AtmActionPacket(AtmActionPacket.ActionType.DEPOSIT, amount.toPlainString(), ""));
        setMessage("Traitement du depot...", 0xAAAAAA);
        amountField.setValue("");
    }

    private void doWithdraw() {
        BigDecimal amount = parseAmount();
        if (amount == null) return;
        ModNetworking.CHANNEL.sendToServer(new AtmActionPacket(AtmActionPacket.ActionType.WITHDRAW, amount.toPlainString(), ""));
        setMessage("Traitement du retrait...", 0xAAAAAA);
        amountField.setValue("");
    }

    private void doTransfer() {
        String target = transferTargetField != null ? transferTargetField.getValue().trim() : "";
        if (target.isEmpty()) {
            setMessage("Numero destinataire vide", 0xFF4444);
            return;
        }

        BigDecimal amount = parseAmount();
        if (amount == null) return;

        ModNetworking.CHANNEL.sendToServer(new AtmActionPacket(AtmActionPacket.ActionType.TRANSFER, amount.toPlainString(), target));
        setMessage("Traitement du virement...", 0xAAAAAA);
        amountField.setValue("");
        transferTargetField.setValue("");
    }

    public void onTransactionResponse(boolean success, double balance, String responseMessage) {
        currentBalance = BigDecimal.valueOf(balance);
        String suffix = success ? ". Solde : " + currentBalance + " EUR" : "";
        setMessage(responseMessage + suffix, success ? 0x44FF44 : 0xFF4444);
    }

    private BigDecimal parseAmount() {
        if (amountField == null) return null;
        String text = amountField.getValue().trim().replace(",", ".");
        if (text.isEmpty()) {
            setMessage("Montant vide", 0xFF4444);
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(text);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                setMessage("Montant invalide", 0xFF4444);
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            setMessage("Format invalide", 0xFF4444);
            return null;
        }
    }

    private void setMessage(String text, int color) {
        message = Component.literal(text);
        messageColorInt = color;
    }

    private void goToMainMenu() {
        state = AtmState.MAIN_MENU;
        setMessage("Solde : " + currentBalance + " EUR", 0xFFFFFF);
        rebuildScreen();
    }

    private void rebuildScreen() {
        clearWidgets();
        pinButtons.clear();
        init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);

        if (state == AtmState.PIN) {
            renderPinScreen(g, mouseX, mouseY, delta);
            return;
        }

        renderMinimalFrame(g);
        renderHeader(g, switch (state) {
            case MAIN_MENU -> "ATM";
            case DEPOSIT -> "Depot";
            case WITHDRAW -> "Retrait";
            case TRANSFER -> "Virement";
            default -> "ATM";
        });
        renderBalance(g);
        renderTransferPrefix(g);
        renderMessage(g, this.height / 2 + 104, 180);

        super.render(g, mouseX, mouseY, delta);
    }

    private void renderPinScreen(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, this.width, this.height, 0x88000000);

        String stars = "*".repeat(pinInput.length()) + "_".repeat(4 - pinInput.length());
        int cx = this.width / 2;
        int cy = this.height / 2;

        g.drawString(this.font, stars, cx - this.font.width(stars) / 2, cy - 76, 0xFFFFFF, false);
        super.render(g, mouseX, mouseY, delta);

        renderMessage(g, cy - 94, 160);
    }

    private void renderMinimalFrame(GuiGraphics g) {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int w = 220;
        int h = 236;
        int x = cx - w / 2;
        int y = cy - h / 2;

        g.fill(x, y, x + w, y + h, 0xB0101010);
        g.fill(x, y, x + w, y + 1, 0xFF505050);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF505050);
        g.fill(x, y, x + 1, y + h, 0xFF505050);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF505050);
    }

    private void renderHeader(GuiGraphics g, String title) {
        g.drawString(this.font, title, this.width / 2 - this.font.width(title) / 2, this.height / 2 - 92, 0xEAEAEA, false);
    }

    private void renderBalance(GuiGraphics g) {
        String balance = "Solde : " + currentBalance + " EUR";
        g.drawString(this.font, balance, this.width / 2 - this.font.width(balance) / 2, this.height / 2 - 74, 0xB8D8B8, false);
    }

    private void renderTransferPrefix(GuiGraphics g) {
        if (state != AtmState.TRANSFER || transferTargetField == null || !transferFieldHasPrefix) {
            return;
        }

        int prefixX = transferTargetField.getX() - this.font.width("ACC-") - 6;
        int prefixY = transferTargetField.getY() + 5;
        g.drawString(this.font, "ACC-", prefixX, prefixY, 0xEAEAEA, false);
    }

    private void renderMessage(GuiGraphics g, int startY, int width) {
        String currentMessage = message.getString();
        if (currentMessage.isBlank() || (state == AtmState.PIN && PIN_HINT.equals(currentMessage))) {
            return;
        }

        List<FormattedCharSequence> lines = this.font.split(message, width);
        int y = startY;
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, this.width / 2 - width / 2, y, messageColorInt, false);
            y += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
