package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class TpePaymentResponsePacket {

    final boolean success;
    final String message;
    final String amount;

    public TpePaymentResponsePacket(boolean success, String message, String amount) {
        this.success = success;
        this.message = message;
        this.amount = amount;
    }

    public static void encode(TpePaymentResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeUtf(packet.message);
        buf.writeUtf(packet.amount);
    }

    public static TpePaymentResponsePacket decode(FriendlyByteBuf buf) {
        return new TpePaymentResponsePacket(
                buf.readBoolean(),
                buf.readUtf(256),
                buf.readUtf(64)
        );
    }

    public static void handle(TpePaymentResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleTpePaymentResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}