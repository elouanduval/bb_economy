package org.bb.bb_economy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.util.function.Supplier;

public class PinCheckPacket {

    private final int pin;

    public int getPin() {
        return pin;
    }

    public PinCheckPacket(int pin) {
        this.pin = pin;
    }

    public static void encode(PinCheckPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.pin);
    }

    public static PinCheckPacket decode(FriendlyByteBuf buf) {
        return new PinCheckPacket(buf.readInt());
    }

    public static void handle(PinCheckPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }

            AtmScreenHandler atmMenu = player.containerMenu instanceof AtmScreenHandler atm && atm.stillValid(player) ? atm : null;
            TpeScreenHandler tpeMenu = player.containerMenu instanceof TpeScreenHandler tpe
                    && tpe.stillValid(player)
                    && tpe.getAccessMode() == TpeScreenHandler.AccessMode.BUYER ? tpe : null;
            if (atmMenu == null && tpeMenu == null) {
                sendResponse(player, false, 0, "Aucun terminal ouvert.");
                return;
            }

            // A l'ATM, le PIN verifie est celui de la carte en main (perso ou entreprise), pas forcement
            // le compte personnel du profil actif. Au TPE, l'acheteur paie toujours depuis son compte perso.
            if (atmMenu != null) {
                BankManager.checkCardPin(player, atmMenu.getCardNumber(), atmMenu.getAccountNumber(), packet.getPin(), outcome -> {
                    atmMenu.setPinValidated(outcome.check().isOk());
                    sendResponse(player, outcome.check().isOk(), outcome.balance().doubleValue(), outcome.check().message());
                });
            } else {
                BankManager.checkPin(player, packet.getPin(), outcome -> {
                    tpeMenu.setPinValidated(outcome.check().isOk());
                    sendResponse(player, outcome.check().isOk(), outcome.balance().doubleValue(), outcome.check().message());
                });
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, double balance, String message) {
        ModNetworking.CHANNEL.sendTo(
                new PinResponsePacket(success, balance, message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
