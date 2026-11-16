package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ShopTradeResponsePacket {

    final boolean success;
    final String message;

    public ShopTradeResponsePacket(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static void encode(ShopTradeResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeUtf(packet.message, 256);
    }

    public static ShopTradeResponsePacket decode(FriendlyByteBuf buf) {
        return new ShopTradeResponsePacket(buf.readBoolean(), buf.readUtf(256));
    }

    public static void handle(ShopTradeResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleShopTradeResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}
