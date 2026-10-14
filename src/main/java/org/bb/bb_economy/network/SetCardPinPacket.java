package org.bb.bb_economy.network;

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
                sendResponse(player, false, "Le PIN doit contenir exactement 4 chiffres.");
                return;
            }
            if (BankManager.isDefaultPin(packet.pin)) {
                sendResponse(player, false, "Ce PIN est reserve. Choisissez-en un autre.");
                return;
            }

            if (player.containerMenu instanceof AtmScreenHandler atmMenu && atmMenu.stillValid(player) && atmMenu.isPinValidated()) {
                // Change le PIN de la carte tenue en main a l'ATM (deja authentifiee) : fonctionne aussi
                // bien pour une carte personnelle que pour une carte d'entreprise, et a tout moment (pas
                // seulement quand le PIN est encore celui par defaut).
                BankManager.updateCardPinByNumber(player, atmMenu.getCardNumber(), packet.pin, success -> {
                    String message = success ? "Le PIN de cette carte a ete change." : "Impossible de changer le PIN.";
                    if (success) player.displayClientMessage(Component.literal(message), false);
                    sendResponse(player, success, message);
                });
                return;
            }

            BankManager.initPersonalPin(player, packet.pin, outcome -> {
                boolean success = outcome == BankManager.InitPinResult.SAVED;
                String message = switch (outcome) {
                    case SAVED -> "Votre code PIN a ete enregistre.";
                    case FAILED -> "Impossible d'enregistrer le PIN.";
                    case NOT_REQUIRED -> "Aucune initialisation de PIN n'est requise actuellement.";
                    case ERROR -> "Service bancaire indisponible. Reessayez plus tard.";
                };
                if (success) player.displayClientMessage(Component.literal(message), false);
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
