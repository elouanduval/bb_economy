package org.bb.bb_economy.network;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientPacketHandler {

    @OnlyIn(Dist.CLIENT)
    public static void handlePinResponse(PinResponsePacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.bb.bb_economy.gui.AtmScreen atmScreen) {
            atmScreen.onPinResponse(packet.success, packet.balance, packet.message);
        } else if (mc.screen instanceof org.bb.bb_economy.gui.TpeScreen tpeScreen) {
            tpeScreen.onPinResponse(packet.success, packet.message);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void handleOpenPinSetup() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new org.bb.bb_economy.gui.PinSetupScreen());
    }

    @OnlyIn(Dist.CLIENT)
    public static void handleSetCardPinResponse(SetCardPinResponsePacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.bb.bb_economy.gui.PinSetupScreen screen) {
            screen.onPinSaved(packet.success, packet.message);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void handleAtmActionResponse(AtmActionResponsePacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.bb.bb_economy.gui.AtmScreen screen) {
            screen.onTransactionResponse(packet.success, packet.balance, packet.message);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void handleTpeSetAmountResponse(TpeSetAmountResponsePacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.bb.bb_economy.gui.TpeScreen screen) {
            screen.onAmountSaved(packet.success, packet.message, packet.amount);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void handleTpePaymentResponse(TpePaymentResponsePacket packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof org.bb.bb_economy.gui.TpeScreen screen) {
            screen.onPaymentResponse(packet.success, packet.message, packet.amount);
        }
    }
}