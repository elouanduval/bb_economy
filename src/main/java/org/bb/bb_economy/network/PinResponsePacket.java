package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PinResponsePacket {

    final boolean success;
    final double balance;
    final String message;

    public PinResponsePacket(boolean success, double balance, String message) {
        this.success = success;
        this.balance = balance;
        this.message = message;
    }

    public static void encode(PinResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeDouble(packet.balance);
        buf.writeUtf(packet.message);
    }

    public static PinResponsePacket decode(FriendlyByteBuf buf) {
        return new PinResponsePacket(buf.readBoolean(), buf.readDouble(), buf.readUtf(256));
    }

    public static void handle(PinResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handlePinResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}
