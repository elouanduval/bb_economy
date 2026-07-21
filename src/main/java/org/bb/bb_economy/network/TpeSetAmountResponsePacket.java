package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class TpeSetAmountResponsePacket {

    final boolean success;
    final String message;
    final String amount;

    public TpeSetAmountResponsePacket(boolean success, String message, String amount) {
        this.success = success;
        this.message = message;
        this.amount = amount;
    }

    public static void encode(TpeSetAmountResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeUtf(packet.message);
        buf.writeUtf(packet.amount);
    }

    public static TpeSetAmountResponsePacket decode(FriendlyByteBuf buf) {
        return new TpeSetAmountResponsePacket(
                buf.readBoolean(),
                buf.readUtf(256),
                buf.readUtf(64)
        );
    }

    public static void handle(TpeSetAmountResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleTpeSetAmountResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}