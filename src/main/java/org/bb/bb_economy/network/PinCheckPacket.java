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

            boolean pinOk = BankManager.checkPin(player, packet.getPin());
            if (player.containerMenu instanceof AtmScreenHandler atmMenu && atmMenu.stillValid(player)) {
                atmMenu.setPinValidated(pinOk);
            } else if (player.containerMenu instanceof TpeScreenHandler tpeMenu
                    && tpeMenu.stillValid(player)
                    && tpeMenu.getAccessMode() == TpeScreenHandler.AccessMode.BUYER) {
                tpeMenu.setPinValidated(pinOk);
            } else {
                ModNetworking.CHANNEL.sendTo(
                        new PinResponsePacket(false, 0),
                        player.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
                return;
            }

            double balance = 0;
            if (pinOk) {
                balance = BankManager.getBalance(player).toBigInteger().doubleValue();
            }

            ModNetworking.CHANNEL.sendTo(
                    new PinResponsePacket(pinOk, balance),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
