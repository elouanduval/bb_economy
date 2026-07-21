package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenPinSetupPacket {

    public static void encode(OpenPinSetupPacket packet, FriendlyByteBuf buf) {
    }

    public static OpenPinSetupPacket decode(FriendlyByteBuf buf) {
        return new OpenPinSetupPacket();
    }

    public static void handle(OpenPinSetupPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleOpenPinSetup())
        );
        ctx.get().setPacketHandled(true);
    }
}