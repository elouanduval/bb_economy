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
import org.bb.bb_economy.network.PinCheckPacket;
import org.bb.bb_economy.network.TpePaymentPacket;
import org.bb.bb_economy.network.TpeSetAmountPacket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class TpeScreen extends Screen implements MenuAccess<TpeScreenHandler> {

    private enum BuyerState {
        PIN,
        CONFIRM
    }

    private final TpeScreenHandler handler;
    private EditBox amountField;
    private Button validateButton;
    private final StringBuilder pinInput = new StringBuilder();
    private final List<Button> pinButtons = new ArrayList<>();
    private BuyerState buyerState = BuyerState.PIN;
    private boolean buyerPinValidated;
    private BigDecimal currentAmount;
    private Component message;
    private int messageColor = 0xEAEAEA;

    public TpeScreen(TpeScreenHandler handler, Inventory inventory, Component title) {
        super(title);
        this.handler = handler;
        this.currentAmount = handler.getPendingAmount();
        this.message = handler.getAccessMode() == TpeScreenHandler.AccessMode.SELLER
                ? Component.literal("Entrez le montant a payer.")
                : Component.literal("Entrez votre code PIN.");
    }

    @Override
    public TpeScreenHandler getMenu() {
        return handler;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        pinButtons.clear();
        amountField = null;
        validateButton = null;

        if (handler.getAccessMode() == TpeScreenHandler.AccessMode.SELLER) {
            initSellerWidgets();
        } else if (buyerState == BuyerState.PIN) {
            initBuyerPinWidgets();
        } else {
            initBuyerConfirmWidgets();
        }
    }

    private void initSellerWidgets() {
        int width = 140;
        int x = this.width / 2 - width / 2;
        int y = this.height / 2 - 18;

        amountField = new EditBox(this.font, x, y, width, 18, Component.literal("Montant"));
        if (currentAmount.compareTo(BigDecimal.ZERO) > 0) {
            amountField.setValue(currentAmount.stripTrailingZeros().toPlainString());
        }
        amountField.setHint(Component.literal("0.00"));
        addRenderableWidget(amountField);

        validateButton = Button.builder(Component.literal("Valider"), button -> submitSellerAmount())
                .pos(x, y + 28)
                .size(width, 20)
                .build();
        addRenderableWidget(validateButton);

        addRenderableWidget(Button.builder(Component.literal("Fermer"), button -> onClose())
                .pos(x, y + 54)
                .size(width, 20)
                .build());
    }

    private void initBuyerPinWidgets() {
        int btnSize = 32;
        int pad = 6;
        int startX = this.width / 2 - (btnSize * 3 + pad * 2) / 2;
        int startY = this.height / 2 - 34;

        for (int i = 1; i <= 9; i++) {
            int col = (i - 1) % 3;
            int row = (i - 1) / 3;
            int x = startX + col * (btnSize + pad);
            int y = startY + row * (btnSize + pad);
            int num = i;
            Button button = Button.builder(Component.literal(String.valueOf(i)), btn -> appendPin(num))
                    .pos(x, y)
                    .size(btnSize, btnSize)
                    .build();
            pinButtons.add(button);
            addRenderableWidget(button);
        }

        Button delete = Button.builder(Component.literal("Eff"), btn -> deletePin())
                .pos(startX, startY + 3 * (btnSize + pad))
                .size(btnSize, btnSize)
                .build();
        pinButtons.add(delete);
        addRenderableWidget(delete);

        Button zero = Button.builder(Component.literal("0"), btn -> appendPin(0))
                .pos(startX + btnSize + pad, startY + 3 * (btnSize + pad))
                .size(btnSize, btnSize)
                .build();
        pinButtons.add(zero);
        addRenderableWidget(zero);

        Button close = Button.builder(Component.literal("Fermer"), btn -> onClose())
                .pos(startX + (btnSize + pad) * 2, startY + 3 * (btnSize + pad))
                .size(btnSize, btnSize)
                .build();
        pinButtons.add(close);
        addRenderableWidget(close);
    }

    private void initBuyerConfirmWidgets() {
        int width = 140;
        int x = this.width / 2 - width / 2;
        int y = this.height / 2 + 8;

        validateButton = Button.builder(Component.literal("Payer"), button -> submitBuyerPayment())
                .pos(x, y)
                .size(width, 20)
                .build();
        validateButton.active = buyerPinValidated && currentAmount.compareTo(BigDecimal.ZERO) > 0;
        addRenderableWidget(validateButton);

        addRenderableWidget(Button.builder(Component.literal("Annuler"), button -> onClose())
                .pos(x, y + 26)
                .size(width, 20)
                .build());
    }

    private void submitSellerAmount() {
        BigDecimal amount = parseAmount();
        if (amount == null) {
            return;
        }

        validateButton.active = false;
        setMessage("Enregistrement du montant...", 0xAAAAAA);
        ModNetworking.CHANNEL.sendToServer(new TpeSetAmountPacket(handler.getPos(), amount.toPlainString()));
    }

    private void submitBuyerPayment() {
        if (!buyerPinValidated) {
            setMessage("PIN non valide.", 0xFF4444);
            return;
        }

        if (currentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            setMessage("Aucun montant n'est en attente sur ce TPE.", 0xFF4444);
            return;
        }

        validateButton.active = false;
        setMessage("Paiement en cours...", 0xAAAAAA);
        ModNetworking.CHANNEL.sendToServer(new TpePaymentPacket(handler.getPos(), currentAmount.toPlainString()));
    }

    private BigDecimal parseAmount() {
        String value = amountField.getValue().trim().replace(",", ".");
        if (value.isEmpty()) {
            setMessage("Montant vide.", 0xFF4444);
            return null;
        }

        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                setMessage("Le montant doit etre superieur a 0.", 0xFF4444);
                return null;
            }
            if (amount.scale() > 2) {
                setMessage("Maximum 2 decimales.", 0xFF4444);
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            setMessage("Format de montant invalide.", 0xFF4444);
            return null;
        }
    }

    private void appendPin(int num) {
        if (pinInput.length() < 4) {
            pinInput.append(num);
            setMessage("Entrez votre code PIN.", 0xEAEAEA);
            if (pinInput.length() == 4) {
                sendPinToServer();
            }
        }
    }

    private void deletePin() {
        if (pinInput.length() > 0) {
            pinInput.deleteCharAt(pinInput.length() - 1);
        }
    }

    private void sendPinToServer() {
        int pin = Integer.parseInt(pinInput.toString());
        setMessage("Verification du PIN...", 0xAAAAAA);
        ModNetworking.CHANNEL.sendToServer(new PinCheckPacket(pin));
    }

    public void onPinResponse(boolean success, String responseMessage) {
        if (!isBuyerMode()) {
            return;
        }

        if (success) {
            buyerPinValidated = true;
            buyerState = BuyerState.CONFIRM;
            setMessage("PIN valide. Vous pouvez payer.", 0x44FF44);
            rebuildScreen();
        } else {
            buyerPinValidated = false;
            pinInput.setLength(0);
            setMessage(responseMessage, 0xFF4444);
        }
    }

    public void onAmountSaved(boolean success, String responseMessage, String amount) {
        if (validateButton != null) {
            validateButton.active = true;
        }

        if (success) {
            currentAmount = new BigDecimal(amount);
            if (amountField != null) {
                amountField.setValue(currentAmount.stripTrailingZeros().toPlainString());
            }
            setMessage(responseMessage, 0x44FF44);
        } else {
            setMessage(responseMessage, 0xFF4444);
        }
    }

    public void onPaymentResponse(boolean success, String responseMessage, String amount) {
        if (validateButton != null) {
            validateButton.active = true;
        }

        currentAmount = new BigDecimal(amount);
        if (success) {
            setMessage(responseMessage, 0x44FF44);
        } else {
            setMessage(responseMessage, 0xFF4444);
        }

        if (handler.getAccessMode() == TpeScreenHandler.AccessMode.BUYER && currentAmount.compareTo(BigDecimal.ZERO) <= 0D) {
            buyerPinValidated = false;
        }

        rebuildScreen();
    }

    private boolean isBuyerMode() {
        return handler.getAccessMode() == TpeScreenHandler.AccessMode.BUYER;
    }

    private void setMessage(String text, int color) {
        this.message = Component.literal(text);
        this.messageColor = color;
    }

    private void rebuildScreen() {
        clearWidgets();
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        renderFrame(graphics);
        renderHeader(graphics);
        renderAmountInfo(graphics);

        if (isBuyerMode() && buyerState == BuyerState.PIN) {
            renderPinMask(graphics);
        } else if (isBuyerMode()) {
            renderBuyerPaymentInfo(graphics);
        }

        renderMessage(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderFrame(GuiGraphics graphics) {
        int w = 240;
        int h = isBuyerMode() && buyerState == BuyerState.PIN ? 255 : 180;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - h / 2;

        graphics.fill(x, y, x + w, y + h, 0xB0101010);
        graphics.fill(x, y, x + w, y + 1, 0xFF505050);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFF505050);
        graphics.fill(x, y, x + 1, y + h, 0xFF505050);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFF505050);
    }

    private void renderHeader(GuiGraphics graphics) {
        String title = handler.getAccessMode() == TpeScreenHandler.AccessMode.SELLER
                ? "TPE vendeur - " + handler.getCompanyId()
                : "TPE acheteur - " + handler.getCompanyId();
        graphics.drawString(this.font, title, this.width / 2 - this.font.width(title) / 2, this.height / 2 - 84, 0xEAEAEA, false);
    }

    private void renderAmountInfo(GuiGraphics graphics) {
        String amountText = "Montant : " + currentAmount.stripTrailingZeros().toPlainString() + " EUR";
        graphics.drawString(this.font, amountText, this.width / 2 - this.font.width(amountText) / 2, this.height / 2 - 64, 0xB8D8B8, false);
    }

    private void renderPinMask(GuiGraphics graphics) {
        String stars = "*".repeat(pinInput.length()) + "_".repeat(4 - pinInput.length());
        graphics.drawString(this.font, stars, this.width / 2 - this.font.width(stars) / 2, this.height / 2 - 54, 0xFFFFFF, false);
    }

    private void renderBuyerPaymentInfo(GuiGraphics graphics) {
        String label = currentAmount.compareTo(BigDecimal.ZERO) > 0
                ? "Validez le paiement ci-dessous."
                : "Aucun montant en attente sur ce TPE.";
        graphics.drawString(this.font, label, this.width / 2 - this.font.width(label) / 2, this.height / 2 - 22, 0xEAEAEA, false);
    }

    private void renderMessage(GuiGraphics graphics) {
        List<FormattedCharSequence> lines = this.font.split(message, 180);
        int y = isBuyerMode() && buyerState == BuyerState.PIN ? this.height / 2 + 118 : this.height / 2 + 68;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, this.width / 2 - 90, y, messageColor, false);
            y += 10;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
