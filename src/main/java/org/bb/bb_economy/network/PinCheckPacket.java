package org.bb.bb_economy.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;
import org.slf4j.Logger;

import java.util.function.Supplier;

public class PinCheckPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

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

            try {
                BankManager.PinCheck check = BankManager.checkPin(player, packet.getPin());
                boolean pinOk = check.isOk();
                if (atmMenu != null) {
                    atmMenu.setPinValidated(pinOk);
                } else {
                    tpeMenu.setPinValidated(pinOk);
                }
                double balance = pinOk ? BankManager.balanceOrZero(player).doubleValue() : 0;
                sendResponse(player, pinOk, balance, check.message());
            } catch (RuntimeException e) {
                LOGGER.error("Erreur pendant la verification du PIN de {}", player.getName().getString(), e);
                sendResponse(player, false, 0, "Service bancaire indisponible. Reessayez plus tard.");
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
