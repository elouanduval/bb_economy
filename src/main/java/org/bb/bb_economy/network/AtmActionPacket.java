package org.bb.bb_economy.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.Money;
import org.bb.bb_economy.database.TxResult;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.init.ModNetworking;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.util.function.Supplier;

public class AtmActionPacket {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ACCOUNT_LENGTH = 20;

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
            try {
                process(player, packet);
            } catch (RuntimeException e) {
                LOGGER.error("Erreur pendant une operation ATM de {}", player.getName().getString(), e);
                sendResponse(player, false, "Service bancaire indisponible. Reessayez plus tard.");
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void process(ServerPlayer player, AtmActionPacket packet) {
        if (!(player.containerMenu instanceof AtmScreenHandler atmMenu) || !atmMenu.stillValid(player)) {
            sendResponse(player, false, "Session ATM invalide.");
            return;
        }
        if (!atmMenu.isPinValidated()) {
            sendResponse(player, false, "PIN ATM non valide.");
            return;
        }

        BigDecimal amount = Money.parsePositive(packet.amount).orElse(null);
        if (amount == null) {
            sendResponse(player, false, "Montant invalide.");
            return;
        }

        TxResult result;
        switch (packet.actionType) {
            case DEPOSIT -> {
                if (!Money.isWhole(amount)) {
                    sendResponse(player, false, "Le depot doit etre un montant entier.");
                    return;
                }
                result = BankManager.deposit(player, amount);
            }
            case WITHDRAW -> {
                if (!Money.isWhole(amount)) {
                    sendResponse(player, false, "Le retrait doit etre un montant entier.");
                    return;
                }
                result = BankManager.withdraw(player, amount);
            }
            case TRANSFER -> {
                String targetAccount = normalizeAccountNumber(packet.targetAccount);
                if (targetAccount == null) {
                    sendResponse(player, false, "Numero de compte invalide.");
                    return;
                }
                result = BankManager.transfer(player, targetAccount, amount);
            }
            default -> {
                sendResponse(player, false, "Operation inconnue.");
                return;
            }
        }

        sendResponse(player, result.isOk(), describe(packet.actionType, result));
    }

    private static String describe(ActionType action, TxResult result) {
        if (result == TxResult.OK) {
            return switch (action) {
                case DEPOSIT -> "Depot effectue.";
                case WITHDRAW -> "Retrait effectue.";
                case TRANSFER -> "Virement effectue.";
            };
        }
        if (result == TxResult.LIMIT_REACHED) {
            return switch (action) {
                case DEPOSIT -> "Limite de depot journaliere atteinte.";
                case WITHDRAW -> "Limite de retrait journaliere atteinte.";
                case TRANSFER -> "Limite de virement journaliere atteinte.";
            };
        }
        return result.message();
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message) {
        ModNetworking.CHANNEL.sendTo(
                new AtmActionResponsePacket(success, BankManager.balanceOrZero(player).doubleValue(), message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }

    /** Retourne le numero de compte normalise, ou null s'il est vide ou manifestement invalide. */
    private static String normalizeAccountNumber(String rawAccount) {
        if (rawAccount == null) {
            return null;
        }
        String value = rawAccount.trim().toUpperCase();
        if (value.isEmpty()) {
            return null;
        }
        if (!value.startsWith("ACC-")) {
            value = "ACC-" + value;
        }
        if (value.length() > MAX_ACCOUNT_LENGTH || !value.matches("[A-Z0-9-]+")) {
            return null;
        }
        return value;
    }
}
