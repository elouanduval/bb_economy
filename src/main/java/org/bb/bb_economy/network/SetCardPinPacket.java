package org.bb.bb_economy.network;

import org.bb.bb_economy.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.util.function.Supplier;

public class SetCardPinPacket {

    private final String pin;

    public SetCardPinPacket(String pin) {
        this.pin = pin;
    }

    public static void encode(SetCardPinPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.pin);
    }

    public static SetCardPinPacket decode(FriendlyByteBuf buf) {
        return new SetCardPinPacket(buf.readUtf(4));
    }

    public static void handle(SetCardPinPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!BankManager.isValidPin(packet.pin)) {
                sendResponse(player, false, Msg.of(Msg.PREFIX + "pin.four_digits"));
                return;
            }
            if (BankManager.isDefaultPin(packet.pin)) {
                sendResponse(player, false, Msg.of(Msg.PREFIX + "pin.reserved"));
                return;
            }

            if (player.containerMenu instanceof AtmScreenHandler atmMenu && atmMenu.stillValid(player) && atmMenu.isPinValidated()) {
                // Change le PIN de la carte du joueur a l'ATM (deja authentifiee), a tout moment : pas seulement
                // quand le PIN est encore celui par defaut.
                BankManager.updateCardPinByNumber(player, atmMenu.getCardNumber(), packet.pin, success -> {
                    String message = success ? Msg.of(Msg.PREFIX + "pin.changed") : Msg.of(Msg.PREFIX + "pin.change_failed");
                    if (success) player.displayClientMessage(Msg.component(message), false);
                    sendResponse(player, success, message);
                });
                return;
            }

            BankManager.initPersonalPin(player, packet.pin, outcome -> {
                boolean success = outcome == BankManager.InitPinResult.SAVED;
                String message = switch (outcome) {
                    case SAVED -> Msg.of(Msg.PREFIX + "pin.saved");
                    case FAILED -> Msg.of(Msg.PREFIX + "pin.save_failed");
                    case NOT_REQUIRED -> Msg.of(Msg.PREFIX + "pin.not_required");
                    case ERROR -> Msg.of(Msg.PREFIX + "tx.service_unavailable");
                };
                if (success) player.displayClientMessage(Msg.component(message), false);
                sendResponse(player, success, message);
            });
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message) {
        ModNetworking.CHANNEL.sendTo(
                new SetCardPinResponsePacket(success, message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
