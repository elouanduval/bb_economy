package org.bb.bb_economy.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.bb.bb_economy.database.Money;
import org.bb.bb_economy.database.ShopCatalog;
import org.bb.bb_economy.database.ShopManager;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.item.ShopItems;
import org.bb.bb_economy.network.PinCheckPacket;
import org.bb.bb_economy.network.ShopTradePacket;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ecran d'une boutique automatique : liste paginee des offres du metier, quantite, mode de paiement
 * (liquide ou carte) et confirmation. Le serveur relit l'offre en base : l'ecran n'envoie qu'un numero d'offre.
 */
public class ShopScreen extends Screen implements MenuAccess<ShopScreenHandler> {

    private static final int ROWS_PER_PAGE = 5;
    private static final int FRAME_WIDTH = 270;
    private static final int FRAME_HEIGHT = 232;

    private final ShopScreenHandler handler;

    private int page;
    private int selected = -1;
    private ShopManager.Payment payment = ShopManager.Payment.CASH;
    private boolean pinValidated;
    private Component message = Component.empty();
    private int messageColor = 0xFFFFFF;

    private EditBox quantityField;
    private EditBox pinField;
    private String lastQuantity = "1";

    public ShopScreen(ShopScreenHandler handler, Inventory inv, Component title) {
        super(title);
        this.handler = handler;
    }

    @Override
    public ShopScreenHandler getMenu() {
        return handler;
    }

    private List<ShopCatalog.Offer> offers() {
        return handler.getOffers();
    }

    private int pageCount() {
        return Math.max(1, (offers().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
    }

    private int top() {
        return this.height / 2 - FRAME_HEIGHT / 2 + 24;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();
        int cx = this.width / 2;
        int top = top();

        page = Math.min(page, pageCount() - 1);
        int first = page * ROWS_PER_PAGE;
        for (int row = 0; row < ROWS_PER_PAGE; row++) {
            int index = first + row;
            if (index >= offers().size()) {
                break;
            }
            ShopCatalog.Offer offer = offers().get(index);
            Button button = Button.builder(Component.literal(rowLabel(offer)), btn -> select(index))
                    .pos(cx - 100, top + row * 22).size(230, 20).build();
            addRenderableWidget(button);
        }

        int navY = top + ROWS_PER_PAGE * 22 + 2;
        Button previous = Button.builder(Component.literal("<"), btn -> changePage(-1))
                .pos(cx - 100, navY).size(20, 18).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), btn -> changePage(1))
                .pos(cx + 110, navY).size(20, 18).build();
        next.active = page < pageCount() - 1;
        addRenderableWidget(next);

        int y = navY + 24;
        quantityField = new EditBox(this.font, cx - 100, y, 50, 18, Component.literal("Nombre de lots"));
        quantityField.setHint(Component.literal("Lots"));
        quantityField.setMaxLength(4);
        quantityField.setFilter(text -> text.chars().allMatch(Character::isDigit));
        quantityField.setValue(lastQuantity);
        quantityField.setResponder(text -> lastQuantity = text);
        addRenderableWidget(quantityField);

        addRenderableWidget(Button.builder(paymentLabel(), btn -> togglePayment())
                .pos(cx - 44, y).size(174, 18).build());
        y += 24;

        pinField = null;
        if (payment == ShopManager.Payment.CARD && !pinValidated) {
            pinField = new EditBox(this.font, cx - 100, y, 90, 18, Component.literal("PIN"));
            pinField.setHint(Component.literal("PIN (4 chiffres)"));
            pinField.setMaxLength(4);
            pinField.setFilter(text -> text.chars().allMatch(Character::isDigit));
            addRenderableWidget(pinField);
            addRenderableWidget(Button.builder(Component.literal("Valider le PIN"), btn -> sendPin())
                    .pos(cx - 4, y).size(134, 18).build());
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal(confirmLabel()), btn -> confirm())
                .pos(cx - 100, y).size(230, 20).build());
    }

    private String rowLabel(ShopCatalog.Offer offer) {
        String verb = offer.direction() == ShopCatalog.Direction.PLAYER_SELLS ? "Vendre" : "Acheter";
        return verb + " " + offer.bundleSize() + " x " + itemName(offer) + " : " + offer.bundlePrice().toPlainString() + " " + Money.CURRENCY;
    }

    private static String itemName(ShopCatalog.Offer offer) {
        return ShopItems.itemOf(offer.itemKey())
                .map(item -> new ItemStack(item).getHoverName().getString())
                .orElse(offer.itemKey());
    }

    private Component paymentLabel() {
        return Component.literal("Paiement : " + (payment == ShopManager.Payment.CASH ? "liquide (billets)" : "carte"));
    }

    private String confirmLabel() {
        if (selected < 0 || selected >= offers().size()) {
            return "Choisissez une offre";
        }
        return offers().get(selected).direction() == ShopCatalog.Direction.PLAYER_SELLS ? "Confirmer la vente" : "Confirmer l'achat";
    }

    private void select(int index) {
        selected = index;
        setMessage("Offre choisie : " + rowLabel(offers().get(index)), 0xFFFFFF);
        rebuild();
    }

    private void changePage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
        rebuild();
    }

    private void togglePayment() {
        payment = payment == ShopManager.Payment.CASH ? ShopManager.Payment.CARD : ShopManager.Payment.CASH;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void sendPin() {
        String text = pinField == null ? "" : pinField.getValue();
        if (text.length() != 4) {
            setMessage("Le PIN doit contenir 4 chiffres.", 0xFF4444);
            return;
        }
        ModNetworking.CHANNEL.sendToServer(new PinCheckPacket(Integer.parseInt(text)));
        setMessage("Verification...", 0xAAAAAA);
    }

    private int quantity() {
        try {
            return Integer.parseInt(quantityField == null ? lastQuantity : quantityField.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void confirm() {
        if (selected < 0 || selected >= offers().size()) {
            setMessage("Choisissez d'abord une offre.", 0xFF4444);
            return;
        }
        int quantity = quantity();
        if (quantity < 1 || quantity > ShopManager.MAX_QUANTITY) {
            setMessage("Nombre de lots invalide (de 1 a " + ShopManager.MAX_QUANTITY + ").", 0xFF4444);
            return;
        }
        if (payment == ShopManager.Payment.CARD && !pinValidated) {
            setMessage("Validez d'abord votre PIN.", 0xFF4444);
            return;
        }
        ModNetworking.CHANNEL.sendToServer(new ShopTradePacket(offers().get(selected).id(), quantity, payment));
        setMessage("Traitement...", 0xAAAAAA);
    }

    public void onPinResponse(boolean success, String responseMessage) {
        pinValidated = success;
        setMessage(success ? "PIN valide. Vous pouvez payer par carte." : responseMessage, success ? 0x44FF44 : 0xFF4444);
        rebuild();
    }

    public void onTradeResponse(boolean success, String responseMessage) {
        setMessage(responseMessage, success ? 0x44FF44 : 0xFF4444);
    }

    private void setMessage(String text, int color) {
        message = Component.literal(text);
        messageColor = color;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        int cx = this.width / 2;
        int x = cx - FRAME_WIDTH / 2;
        int y = this.height / 2 - FRAME_HEIGHT / 2;

        g.fill(x, y, x + FRAME_WIDTH, y + FRAME_HEIGHT, 0xB0101010);
        g.fill(x, y, x + FRAME_WIDTH, y + 1, 0xFF505050);
        g.fill(x, y + FRAME_HEIGHT - 1, x + FRAME_WIDTH, y + FRAME_HEIGHT, 0xFF505050);
        g.fill(x, y, x + 1, y + FRAME_HEIGHT, 0xFF505050);
        g.fill(x + FRAME_WIDTH - 1, y, x + FRAME_WIDTH, y + FRAME_HEIGHT, 0xFF505050);

        String title = "Boutique - " + handler.getCompanyName();
        g.drawString(this.font, title, cx - this.font.width(title) / 2, y + 8, 0xEAEAEA, false);

        if (offers().isEmpty()) {
            String empty = "Aucune offre disponible pour le moment.";
            g.drawString(this.font, empty, cx - this.font.width(empty) / 2, top() + 40, 0xAAAAAA, false);
        }

        // Icone de chaque offre, a gauche de son bouton.
        int first = page * ROWS_PER_PAGE;
        for (int row = 0; row < ROWS_PER_PAGE && first + row < offers().size(); row++) {
            int index = first + row;
            ShopItems.itemOf(offers().get(index).itemKey())
                    .ifPresent(item -> g.renderItem(new ItemStack(item), cx - 124, top() + (index - first) * 22 + 2));
            if (index == selected) {
                g.fill(cx - 128, top() + (index - first) * 22, cx - 126, top() + (index - first) * 22 + 20, 0xFF44FF44);
            }
        }

        String pageLabel = "Page " + (page + 1) + "/" + pageCount();
        g.drawString(this.font, pageLabel, cx + 5 - this.font.width(pageLabel) / 2, top() + ROWS_PER_PAGE * 22 + 7, 0xAAAAAA, false);

        if (selected >= 0 && selected < offers().size()) {
            ShopCatalog.Offer chosen = offers().get(selected);
            int lots = Math.max(quantity(), 0);
            BigDecimal total = chosen.bundlePrice().multiply(BigDecimal.valueOf(lots));
            String totalLabel = "Total : " + (long) lots * chosen.bundleSize() + " objets pour "
                    + total.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " " + Money.CURRENCY;
            g.drawString(this.font, totalLabel, cx - 100, y + FRAME_HEIGHT - 38, 0xB8D8B8, false);
        }

        List<FormattedCharSequence> lines = this.font.split(message, FRAME_WIDTH - 20);
        int lineY = y + FRAME_HEIGHT - 26;
        for (FormattedCharSequence line : lines) {
            g.drawString(this.font, line, x + 10, lineY, messageColor, false);
            lineY += 10;
        }

        super.render(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
