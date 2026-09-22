package org.bb.bb_economy.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.init.ModNetworking;
import org.slf4j.Logger;

import java.util.function.Supplier;

public class SetCardPinPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

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

            boolean success = false;
            String message;

            try {
                if (!BankManager.isValidPin(packet.pin)) {
                    message = "Le PIN doit contenir exactement 4 chiffres.";
                } else if (BankManager.isDefaultPin(packet.pin)) {
                    message = "Ce PIN est reserve. Choisissez-en un autre.";
                } else if (!BankManager.hasDefaultCardPin(player)) {
                    message = "Aucune initialisation de PIN n'est requise actuellement.";
                } else {
                    success = BankManager.updateCardPin(player, packet.pin);
                    message = success
                            ? "Votre code PIN a ete enregistre."
                            : "Impossible d'enregistrer le PIN.";
                }
            } catch (RuntimeException e) {
                LOGGER.error("Erreur pendant l'enregistrement du PIN de {}", player.getName().getString(), e);
                message = "Service bancaire indisponible. Reessayez plus tard.";
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
