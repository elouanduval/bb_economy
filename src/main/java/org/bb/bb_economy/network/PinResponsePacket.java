package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PinResponsePacket {

    final boolean success;
    final double balance;

    public PinResponsePacket(boolean success, double balance) {
        this.success = success;
        this.balance = balance;
    }

    public static void encode(PinResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeDouble(packet.balance);
    }

    public static PinResponsePacket decode(FriendlyByteBuf buf) {
        return new PinResponsePacket(buf.readBoolean(), buf.readDouble());
    }

    public static void handle(PinResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handlePinResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}
