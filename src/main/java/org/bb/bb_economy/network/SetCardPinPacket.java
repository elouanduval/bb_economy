package org.bb.bb_economy.network;

import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
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

            boolean success;
            String message;

            if (!BankManager.isValidPin(packet.pin)) {
                success = false;
                message = "Le PIN doit contenir exactement 4 chiffres.";
            } else if (!BankManager.hasDefaultCardPin(player)) {
                success = false;
                message = "Aucune initialisation de PIN n'est requise actuellement.";
            } else {
                success = BankManager.updateCardPin(player, packet.pin);
                message = success
                        ? "Votre code PIN a ete enregistre."
                        : "Impossible d'enregistrer le PIN.";
            }

            if (success) {
                player.displayClientMessage(Component.literal(message), false);
            }

            ModNetworking.CHANNEL.sendTo(
                    new SetCardPinResponsePacket(success, message),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
