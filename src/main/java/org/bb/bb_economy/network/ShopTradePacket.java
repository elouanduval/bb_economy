package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.ShopManager;
import org.bb.bb_economy.gui.ShopScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.util.function.Supplier;

/** Demande d'echange a une boutique automatique : une offre, une quantite, un mode de paiement. */
public class ShopTradePacket {

    private final int offerId;
    private final int quantity;
    private final ShopManager.Payment payment;

    public ShopTradePacket(int offerId, int quantity, ShopManager.Payment payment) {
        this.offerId = offerId;
        this.quantity = quantity;
        this.payment = payment;
    }

    public static void encode(ShopTradePacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.offerId);
        buf.writeInt(packet.quantity);
        buf.writeEnum(packet.payment);
    }

    public static ShopTradePacket decode(FriendlyByteBuf buf) {
        return new ShopTradePacket(buf.readInt(), buf.readInt(), buf.readEnum(ShopManager.Payment.class));
    }

    public static void handle(ShopTradePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof ShopScreenHandler shop) || !shop.stillValid(player)) {
                sendResponse(player, false, "Session de boutique invalide.");
                return;
            }
            // L'offre est relue en base et verifiee contre l'entreprise de CE bloc : le client ne choisit que
            // un numero d'offre, jamais un prix ni un objet.
            ShopManager.trade(player, shop.getCompanyId(), packet.offerId, packet.quantity, packet.payment,
                    shop.isPinValidated(), result -> sendResponse(player, result.success(), result.message()));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message) {
        ModNetworking.CHANNEL.sendTo(
                new ShopTradeResponsePacket(success, message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
