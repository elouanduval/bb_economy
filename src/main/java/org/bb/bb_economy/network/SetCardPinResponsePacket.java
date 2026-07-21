package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetCardPinResponsePacket {

    final boolean success;
    final String message;

    public SetCardPinResponsePacket(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static void encode(SetCardPinResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success);
        buf.writeUtf(packet.message);
    }

    public static SetCardPinResponsePacket decode(FriendlyByteBuf buf) {
        return new SetCardPinResponsePacket(buf.readBoolean(), buf.readUtf(256));
    }

    public static void handle(SetCardPinResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleSetCardPinResponse(packet))
        );
        ctx.get().setPacketHandled(true);
    }
}