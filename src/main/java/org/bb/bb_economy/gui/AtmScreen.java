package org.bb.bb_economy.gui;

import org.bb.bb_economy.Msg;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.bb.bb_economy.database.Money;
import org.bb.bb_economy.database.CompanyChoice;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.network.AtmActionPacket;
import org.bb.bb_economy.network.AtmSelectAccountPacket;
import org.bb.bb_economy.network.PinCheckPacket;
import org.bb.bb_economy.network.SetCardPinPacket;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class AtmScreen extends Screen implements MenuAccess<AtmScreenHandler> {

    private static String pinHint() {
        return tr("gui.enter_pin");
    }

    /** Texte traduit dans la langue du joueur (les cles sont dans assets/bb_economy/lang). */
    private static String tr(String key, Object... args) {
        return Component.translatable(Msg.PREFIX + key, args).getString();
    }

    private final AtmScreenHandler handler;

    private enum AtmState { PIN, ACCOUNT_SELECT, MAIN_MENU, DEPOSIT, WITHDRAW, TRANSFER, CHANGE_PIN }

    private static String personalLabel() {
        return tr("gui.personal_account");
    }
    private static final int MAX_COMPANY_BUTTONS = 8;

    /** Entreprises dont le joueur est le patron (envoyees avec la reponse au PIN) ; vide pour un joueur normal. */
    private List<CompanyChoice> companies = List.of();
    private String accountLabel = personalLabel();
    private String pendingAccountLabel = personalLabel();
    /** Vrai entre l'envoi d'un choix de compte et sa reponse, pour ne pas confondre avec une reponse d'operation. */
    private boolean awaitingAccountSelection;

    private AtmState state = AtmState.PIN;
    private final StringBuilder pinInput = new StringBuilder();
    private BigDecimal currentBalance = BigDecimal.ZERO;
    private Component message = Component.literal(pinHint());
    private int messageColorInt = 0xFFFFFF;

    private final List<Button> pinButtons = new ArrayList<>();
    private EditBox amountField;
    private EditBox transferTargetField;
    private boolean transferFieldHasPrefix;
    private EditBox newPinField;

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
        newPinField = null;

        switch (state) {
            case PIN -> buildPinScreen();
            case MAIN_MENU -> buildMainMenu();
            case DEPOSIT -> buildAmountScreen(tr("gui.deposit"), false);
            case WITHDRAW -> buildAmountScreen(tr("gui.withdraw"), false);
            case TRANSFER -> buildAmountScreen(tr("gui.transfer"), true);
            case CHANGE_PIN -> buildChangePinScreen();
            case ACCOUNT_SELECT -> buildAccountSelectScreen();
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

        Button delete = Button.builder(Component.translatable(Msg.PREFIX + "gui.key_delete"), btn -> deletePin())
                .pos(startX, startY + 3 * (btnSize + pad)).size(btnSize, btnSize).build();
        pinButtons.add(delete);
        addRenderableWidget(delete);

        Button zero = Button.builder(Component.literal("0"), btn -> appendPin(0))
                .pos(startX + btnSize + pad, startY + 3 * (btnSize + pad)).size(btnSize, btnSize).build();
        pinButtons.add(zero);
        addRenderableWidget(zero);

        Button close = Button.builder(Component.translatable(Msg.PREFIX + "gui.key_close"), btn -> onClose())
                .pos(startX + (btnSize + pad) * 2, startY + 3 * (btnSize + pad)).size(btnSize, btnSize).build();
        pinButtons.add(close);
        addRenderableWidget(close);
    }

    private void buildMainMenu() {
        int cx = this.width / 2;
        int y = this.height / 2 - 36;
        int step = 22;
        int width = 120;
        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.deposit"), btn -> { state = AtmState.DEPOSIT; rebuildScreen(); })
                .pos(cx - width / 2, y).size(width, 20).build());
        y += step;
        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.withdraw"), btn -> { state = AtmState.WITHDRAW; rebuildScreen(); })
                .pos(cx - width / 2, y).size(width, 20).build());
        y += step;
        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.transfer"), btn -> { state = AtmState.TRANSFER; rebuildScreen(); })
                .pos(cx - width / 2, y).size(width, 20).build());
        y += step;
        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.change_pin"), btn -> { state = AtmState.CHANGE_PIN; rebuildScreen(); })
                .pos(cx - width / 2, y).size(width, 20).build());
        y += step;
        if (!companies.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.change_account"), btn -> { state = AtmState.ACCOUNT_SELECT; rebuildScreen(); })
                    .pos(cx - width / 2, y).size(width, 20).build());
            y += step;
        }
        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.close"), btn -> onClose())
                .pos(cx - width / 2, y + 6).size(width, 20).build());
    }

    /** Choix entre le compte personnel et celui de chaque entreprise dont le joueur est le patron. */
    private void buildAccountSelectScreen() {
        int cx = this.width / 2;
        int y = this.height / 2 - 52;
        int width = 150;

        addRenderableWidget(Button.builder(Component.literal(personalLabel()), btn -> selectAccount(AtmSelectAccountPacket.PERSONAL, personalLabel()))
                .pos(cx - width / 2, y).size(width, 20).build());
        y += 22;
        int shown = 0;
        for (CompanyChoice company : companies) {
            if (shown++ >= MAX_COMPANY_BUTTONS) {
                break;
            }
            String label = company.name();
            addRenderableWidget(Button.builder(Component.literal(label), btn -> selectAccount(company.id(), label))
                    .pos(cx - width / 2, y).size(width, 20).build());
            y += 22;
        }
    }

    private void selectAccount(int companyId, String label) {
        pendingAccountLabel = label;
        awaitingAccountSelection = true;
        ModNetworking.CHANNEL.sendToServer(new AtmSelectAccountPacket(companyId));
        setMessage(Msg.of(Msg.PREFIX + "gui.loading"), 0xAAAAAA);
    }

    private void buildChangePinScreen() {
        int cx = this.width / 2;
        int cy = this.height / 2 - 12;
        int width = 132;

        newPinField = new EditBox(this.font, cx - width / 2, cy, width, 18, Component.translatable(Msg.PREFIX + "gui.new_pin"));
        newPinField.setHint(Component.translatable(Msg.PREFIX + "gui.new_pin_hint"));
        newPinField.setMaxLength(4);
        newPinField.setFilter(text -> text.chars().allMatch(Character::isDigit));
        addRenderableWidget(newPinField);

        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.confirm"), btn -> doChangePin())
                .pos(cx - width / 2, cy + 28).size(width, 20).build());

        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.back"), btn -> goToMainMenu())
                .pos(cx - width / 2, cy + 54).size(width, 20).build());
    }

    private void buildAmountScreen(String action, boolean transfer) {
        int cx = this.width / 2;
        int cy = this.height / 2 - (transfer ? 30 : 12);
        int width = 132;

        if (transfer) {
            int prefixWidth = this.font.width("ACC-") + 8;
            transferTargetField = new EditBox(this.font, cx - width / 2 + prefixWidth, cy, width - prefixWidth, 18, Component.translatable(Msg.PREFIX + "gui.account"));
            transferTargetField.setHint(Component.translatable(Msg.PREFIX + "gui.number"));
            transferFieldHasPrefix = true;
            addRenderableWidget(transferTargetField);
            cy += 28;
        }

        amountField = new EditBox(this.font, cx - width / 2, cy, width, 18, Component.translatable(Msg.PREFIX + "gui.amount"));
        amountField.setHint(Component.translatable(Msg.PREFIX + "gui.amount"));
        addRenderableWidget(amountField);

        addRenderableWidget(Button.builder(Component.literal(action), btn -> {
            switch (state) {
                case DEPOSIT -> doDeposit();
                case WITHDRAW -> doWithdraw();
                case TRANSFER -> doTransfer();
                default -> { }
            }
        }).pos(cx - width / 2, cy + 28).size(width, 20).build());

        addRenderableWidget(Button.builder(Component.translatable(Msg.PREFIX + "gui.back"), btn -> goToMainMenu())
                .pos(cx - width / 2, cy + 54).size(width, 20).build());
    }

    private void appendPin(int num) {
        if (pinInput.length() < 4) {
            pinInput.append(num);
            setMessage(pinHint(), 0xFFFFFF);
            if (pinInput.length() == 4) sendPinToServer();
        }
    }

    private void deletePin() {
        if (pinInput.length() > 0) pinInput.deleteCharAt(pinInput.length() - 1);
    }

    private void sendPinToServer() {
        int pin = Integer.parseInt(pinInput.toString());
        ModNetworking.CHANNEL.sendToServer(new PinCheckPacket(pin));
        setMessage(Msg.of(Msg.PREFIX + "gui.checking"), 0xAAAAAA);
    }

    public void onPinResponse(boolean success, double balance, String responseMessage, List<CompanyChoice> ownedCompanies) {
        if (success) {
            currentBalance = BigDecimal.valueOf(balance);
            companies = List.copyOf(ownedCompanies);
            accountLabel = personalLabel();
            // Un patron choisit d'abord son compte ; les autres joueurs arrivent directement sur le menu.
            state = companies.isEmpty() ? AtmState.MAIN_MENU : AtmState.ACCOUNT_SELECT;
            setMessage(companies.isEmpty() ? Msg.of(Msg.PREFIX + "gui.balance", currentBalance + " " + Money.CURRENCY) : Msg.of(Msg.PREFIX + "gui.choose_account"), 0xFFFFFF);
            rebuildScreen();
        } else {
            setMessage(responseMessage, 0xFF4444);
            pinInput.setLength(0);
        }
    }

    private void doDeposit() {
        BigDecimal amount = parseAmount();
        if (amount == null) return;
        ModNetworking.CHANNEL.sendToServer(new AtmActionPacket(AtmActionPacket.ActionType.DEPOSIT, amount.toPlainString(), ""));
        setMessage(Msg.of(Msg.PREFIX + "gui.processing_deposit"), 0xAAAAAA);
        amountField.setValue("");
    }

    private void doWithdraw() {
        BigDecimal amount = parseAmount();
        if (amount == null) return;
        ModNetworking.CHANNEL.sendToServer(new AtmActionPacket(AtmActionPacket.ActionType.WITHDRAW, amount.toPlainString(), ""));
        setMessage(Msg.of(Msg.PREFIX + "gui.processing_withdraw"), 0xAAAAAA);
        amountField.setValue("");
    }

    private void doTransfer() {
        String target = transferTargetField != null ? transferTargetField.getValue().trim() : "";
        if (target.isEmpty()) {
            setMessage(Msg.of(Msg.PREFIX + "gui.target_empty"), 0xFF4444);
            return;
        }

        BigDecimal amount = parseAmount();
        if (amount == null) return;

        ModNetworking.CHANNEL.sendToServer(new AtmActionPacket(AtmActionPacket.ActionType.TRANSFER, amount.toPlainString(), target));
        setMessage(Msg.of(Msg.PREFIX + "gui.processing_transfer"), 0xAAAAAA);
        amountField.setValue("");
        transferTargetField.setValue("");
    }

    public void onTransactionResponse(boolean success, double balance, String responseMessage) {
        // Reponse au choix d'un compte (le serveur repond avec le meme paquet que pour une operation).
        if (state == AtmState.ACCOUNT_SELECT && awaitingAccountSelection) {
            awaitingAccountSelection = false;
            if (success) {
                accountLabel = pendingAccountLabel;
                currentBalance = BigDecimal.valueOf(balance);
                state = AtmState.MAIN_MENU;
                setMessage(Msg.of(Msg.PREFIX + "gui.balance", currentBalance + " " + Money.CURRENCY), 0xFFFFFF);
                rebuildScreen();
            } else {
                setMessage(responseMessage, 0xFF4444);
            }
            return;
        }
        currentBalance = BigDecimal.valueOf(balance);
        Component suffix = success ? Component.literal(" ").append(Component.translatable(Msg.PREFIX + "gui.balance", currentBalance + " " + Money.CURRENCY)) : Component.empty();
        setMessage(Msg.component(responseMessage).copy().append(suffix), success ? 0x44FF44 : 0xFF4444);
    }

    private void doChangePin() {
        String pin = newPinField != null ? newPinField.getValue().trim() : "";
        if (pin.length() != 4) {
            setMessage(Msg.of(Msg.PREFIX + "pin.four_digits"), 0xFF4444);
            return;
        }
        ModNetworking.CHANNEL.sendToServer(new SetCardPinPacket(pin));
        setMessage(Msg.of(Msg.PREFIX + "gui.saving"), 0xAAAAAA);
    }

    public void onPinChangeResponse(boolean success, String responseMessage) {
        setMessage(responseMessage, success ? 0x44FF44 : 0xFF4444);
        if (success && newPinField != null) {
            newPinField.setValue("");
        }
    }

    private BigDecimal parseAmount() {
        if (amountField == null) return null;
        String text = amountField.getValue().trim().replace(",", ".");
        if (text.isEmpty()) {
            setMessage(Msg.of(Msg.PREFIX + "gui.amount_empty"), 0xFF4444);
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(text);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                setMessage(Msg.of(Msg.PREFIX + "tx.invalid_amount"), 0xFF4444);
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            setMessage(Msg.of(Msg.PREFIX + "gui.invalid_format"), 0xFF4444);
            return null;
        }
    }

    private void setMessage(String text, int color) {
        setMessage(Msg.component(text), color);
    }

    private void setMessage(Component text, int color) {
        message = text;
        messageColorInt = color;
    }

    private void goToMainMenu() {
        state = AtmState.MAIN_MENU;
        setMessage(Msg.of(Msg.PREFIX + "gui.balance", currentBalance + " " + Money.CURRENCY), 0xFFFFFF);
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
            case DEPOSIT -> tr("gui.deposit");
            case WITHDRAW -> tr("gui.withdraw");
            case TRANSFER -> tr("gui.transfer");
            case CHANGE_PIN -> tr("gui.change_pin");
            case ACCOUNT_SELECT -> tr("gui.choose_account_title");
            default -> "ATM";
        });
        if (state != AtmState.ACCOUNT_SELECT) {
            renderBalance(g);
        }
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
        int h = 256;
        int x = cx - w / 2;
        int y = cy - h / 2;

        g.fill(x, y, x + w, y + h, 0xB0101010);
        g.fill(x, y, x + w, y + 1, 0xFF505050);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF505050);
        g.fill(x, y, x + 1, y + h, 0xFF505050);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF505050);
    }

    private void renderHeader(GuiGraphics g, String title) {
        // Un patron voit sur quel compte il opere ; les autres joueurs gardent l'en-tete habituel.
        if (!companies.isEmpty() && state != AtmState.ACCOUNT_SELECT) {
            title = title + " - " + accountLabel;
        }
        g.drawString(this.font, title, this.width / 2 - this.font.width(title) / 2, this.height / 2 - 92, 0xEAEAEA, false);
    }

    private void renderBalance(GuiGraphics g) {
        String balance = tr("gui.balance", currentBalance + " " + Money.CURRENCY);
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
        if (currentMessage.isBlank() || (state == AtmState.PIN && pinHint().equals(currentMessage))) {
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
