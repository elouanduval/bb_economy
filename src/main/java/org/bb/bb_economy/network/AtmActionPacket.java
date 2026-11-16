package org.bb.bb_economy.network;

import org.bb.bb_economy.Msg;
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
                // Erreur synchrone (validation avant meme de partir en base) : le seul cas restant ici.
                LOGGER.error("Erreur pendant une operation ATM de {}", player.getName().getString(), e);
                sendResponse(player, null, false, Msg.of(Msg.PREFIX + "tx.service_unavailable"));
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void process(ServerPlayer player, AtmActionPacket packet) {
        if (!(player.containerMenu instanceof AtmScreenHandler atmMenu) || !atmMenu.stillValid(player)) {
            sendResponse(player, null, false, Msg.of(Msg.PREFIX + "atm.invalid_session"));
            return;
        }
        if (!atmMenu.isPinValidated()) {
            sendResponse(player, atmMenu.getAccountNumber(), false, Msg.of(Msg.PREFIX + "atm.pin_not_valid"));
            return;
        }

        BigDecimal amount = Money.parsePositive(packet.amount).orElse(null);
        if (amount == null) {
            sendResponse(player, atmMenu.getAccountNumber(), false, Msg.of(Msg.PREFIX + "tx.invalid_amount"));
            return;
        }

        String accountNumber = atmMenu.getAccountNumber();
        switch (packet.actionType) {
            case DEPOSIT -> {
                if (!Money.isWhole(amount)) {
                    sendResponse(player, accountNumber, false, Msg.of(Msg.PREFIX + "atm.deposit_whole"));
                    return;
                }
                BankManager.depositToAccount(player, accountNumber, amount, outcome ->
                        sendResponse(player, outcome.result(), outcome.balance(), describe(ActionType.DEPOSIT, outcome.result())));
            }
            case WITHDRAW -> {
                if (!Money.isWhole(amount)) {
                    sendResponse(player, accountNumber, false, Msg.of(Msg.PREFIX + "atm.withdraw_whole"));
                    return;
                }
                BankManager.withdrawFromAccount(player, accountNumber, amount, outcome ->
                        sendResponse(player, outcome.result(), outcome.balance(), describe(ActionType.WITHDRAW, outcome.result())));
            }
            case TRANSFER -> {
                String targetAccount = normalizeAccountNumber(packet.targetAccount);
                if (targetAccount == null) {
                    sendResponse(player, accountNumber, false, Msg.of(Msg.PREFIX + "atm.invalid_account_number"));
                    return;
                }
                BankManager.transferFromAccount(player, accountNumber, targetAccount, amount, outcome ->
                        sendResponse(player, outcome.result(), outcome.balance(), describe(ActionType.TRANSFER, outcome.result())));
            }
            default -> sendResponse(player, accountNumber, false, Msg.of(Msg.PREFIX + "atm.unknown_operation"));
        }
    }

    private static String describe(ActionType action, TxResult result) {
        if (result == TxResult.OK) {
            return switch (action) {
                case DEPOSIT -> Msg.of(Msg.PREFIX + "atm.deposit_done");
                case WITHDRAW -> Msg.of(Msg.PREFIX + "atm.withdraw_done");
                case TRANSFER -> Msg.of(Msg.PREFIX + "atm.transfer_done");
            };
        }
        if (result == TxResult.LIMIT_REACHED) {
            return switch (action) {
                case DEPOSIT -> Msg.of(Msg.PREFIX + "atm.deposit_limit");
                case WITHDRAW -> Msg.of(Msg.PREFIX + "atm.withdraw_limit");
                case TRANSFER -> Msg.of(Msg.PREFIX + "atm.transfer_limit");
            };
        }
        return result.message();
    }

    /** Variante utilisee quand le resultat (et donc le solde) sont deja connus. */
    private static void sendResponse(ServerPlayer player, TxResult result, BigDecimal balance, String message) {
        ModNetworking.CHANNEL.sendTo(
                new AtmActionResponsePacket(result.isOk(), balance.doubleValue(), message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }

    /**
     * Variante de rejet immediat (avant tout appel base) : accountNumber peut etre null si la session ATM
     * elle-meme est invalide (aucun compte a afficher). Seul cas ou une lecture de solde reste synchrone,
     * sur un chemin d'echec instantane, pas la boucle chaude.
     */
    private static void sendResponse(ServerPlayer player, String accountNumber, boolean success, String message) {
        double balance = accountNumber != null ? BankManager.accountBalanceOrZero(accountNumber).doubleValue() : 0;
        ModNetworking.CHANNEL.sendTo(
                new AtmActionResponsePacket(success, balance, message),
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
