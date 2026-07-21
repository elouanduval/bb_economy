package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.math.BigDecimal;
import java.util.function.Supplier;

public class AtmActionPacket {

    public enum ActionType {
        DEPOSIT,
        WITHDRAW,
        TRANSFER
    }

    private final ActionType actionType;
    private final String amount;
    private final String targetAccount;

    public AtmActionPacket(ActionType actionType, String amount, String targetAccount) {
        this.actionType = actionType;
        this.amount = amount;
        this.targetAccount = targetAccount;
    }

    public static void encode(AtmActionPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.actionType);
        buf.writeUtf(packet.amount);
        buf.writeUtf(packet.targetAccount);
    }

    public static AtmActionPacket decode(FriendlyByteBuf buf) {
        return new AtmActionPacket(
                buf.readEnum(ActionType.class),
                buf.readUtf(64),
                buf.readUtf(64)
        );
    }

    public static void handle(AtmActionPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof AtmScreenHandler atmMenu) || !atmMenu.stillValid(player)) {
                sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Session ATM invalide.");
                return;
            }
            if (!atmMenu.isPinValidated()) {
                sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "PIN ATM non valide.");
                return;
            }

            boolean success = false;
            String message;

            BigDecimal amount;
            try {
                amount = new BigDecimal(packet.amount);
            } catch (NumberFormatException e) {
                sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Montant invalide.");
                return;
            }

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Montant invalide.");
                return;
            }

            switch (packet.actionType) {
                case DEPOSIT -> {
                    if (amount.stripTrailingZeros().scale() > 0) {
                        sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Le depot doit etre un montant entier.");
                        return;
                    }
                    message = BankManager.validateDeposit(player, amount);
                    if (message != null) {
                        sendResponse(player, false, BankManager.getBalance(player).doubleValue(), message);
                        return;
                    }
                    success = BankManager.deposit(player, amount);
                    message = success ? "Depot effectue." : "Pas assez de billets.";
                }
                case WITHDRAW -> {
                    if (amount.stripTrailingZeros().scale() > 0) {
                        sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Le retrait doit etre un montant entier.");
                        return;
                    }
                    message = BankManager.validateWithdraw(player, amount);
                    if (message != null) {
                        sendResponse(player, false, BankManager.getBalance(player).doubleValue(), message);
                        return;
                    }
                    success = BankManager.withdraw(player, amount);
                    message = success ? "Retrait effectue." : "Solde insuffisant.";
                }
                case TRANSFER -> {
                    if (packet.targetAccount == null || packet.targetAccount.isBlank()) {
                        sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Numero de compte vide.");
                        return;
                    }
                    String targetAccount = normalizeAccountNumber(packet.targetAccount);
                    message = BankManager.validateTransfer(player, amount);
                    if (message != null) {
                        sendResponse(player, false, BankManager.getBalance(player).doubleValue(), message);
                        return;
                    }
                    success = BankManager.transfer(player, targetAccount, amount);
                    message = success ? "Virement effectue." : "Virement impossible.";
                }
                default -> {
                    sendResponse(player, false, BankManager.getBalance(player).doubleValue(), "Operation inconnue.");
                    return;
                }
            }

            sendResponse(player, success, BankManager.getBalance(player).doubleValue(), message);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, double balance, String message) {
        ModNetworking.CHANNEL.sendTo(
                new AtmActionResponsePacket(success, balance, message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }

    private static String normalizeAccountNumber(String rawAccount) {
        String value = rawAccount.trim().toUpperCase();
        if (value.startsWith("ACC-")) {
            return value;
        }
        return "ACC-" + value;
    }
}
