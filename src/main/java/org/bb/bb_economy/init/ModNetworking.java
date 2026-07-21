package org.bb.bb_economy.init;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.bb.bb_economy.network.AtmActionPacket;
import org.bb.bb_economy.network.AtmActionResponsePacket;
import org.bb.bb_economy.network.OpenPinSetupPacket;
import org.bb.bb_economy.network.PinCheckPacket;
import org.bb.bb_economy.network.PinResponsePacket;
import org.bb.bb_economy.network.SetCardPinPacket;
import org.bb.bb_economy.network.SetCardPinResponsePacket;
import org.bb.bb_economy.network.TpePaymentPacket;
import org.bb.bb_economy.network.TpePaymentResponsePacket;
import org.bb.bb_economy.network.TpeSetAmountPacket;
import org.bb.bb_economy.network.TpeSetAmountResponsePacket;

public class ModNetworking {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.tryParse("bb_economy:main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int id = 0;

    public static void register() {
        CHANNEL.registerMessage(id++,
                PinCheckPacket.class,
                PinCheckPacket::encode,
                PinCheckPacket::decode,
                PinCheckPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(id++,
                PinResponsePacket.class,
                PinResponsePacket::encode,
                PinResponsePacket::decode,
                PinResponsePacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(id++,
                OpenPinSetupPacket.class,
                OpenPinSetupPacket::encode,
                OpenPinSetupPacket::decode,
                OpenPinSetupPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(id++,
                SetCardPinPacket.class,
                SetCardPinPacket::encode,
                SetCardPinPacket::decode,
                SetCardPinPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(id++,
                SetCardPinResponsePacket.class,
                SetCardPinResponsePacket::encode,
                SetCardPinResponsePacket::decode,
                SetCardPinResponsePacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(id++,
                AtmActionPacket.class,
                AtmActionPacket::encode,
                AtmActionPacket::decode,
                AtmActionPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(id++,
                AtmActionResponsePacket.class,
                AtmActionResponsePacket::encode,
                AtmActionResponsePacket::decode,
                AtmActionResponsePacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(id++,
                TpeSetAmountPacket.class,
                TpeSetAmountPacket::encode,
                TpeSetAmountPacket::decode,
                TpeSetAmountPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(id++,
                TpeSetAmountResponsePacket.class,
                TpeSetAmountResponsePacket::encode,
                TpeSetAmountResponsePacket::decode,
                TpeSetAmountResponsePacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(id++,
                TpePaymentPacket.class,
                TpePaymentPacket::encode,
                TpePaymentPacket::decode,
                TpePaymentPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(id++,
                TpePaymentResponsePacket.class,
                TpePaymentResponsePacket::encode,
                TpePaymentResponsePacket::decode,
                TpePaymentResponsePacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }
}
