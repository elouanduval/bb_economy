package org.bb.bb_economy.network;

import org.bb.bb_economy.Msg;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.CompanyChoice;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.gui.ShopScreenHandler;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.util.List;
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
            ShopScreenHandler shopMenu = player.containerMenu instanceof ShopScreenHandler shop && shop.stillValid(player) ? shop : null;
            if (atmMenu == null && tpeMenu == null && shopMenu == null) {
                sendResponse(player, false, 0, Msg.of(Msg.PREFIX + "pin.no_terminal"), List.of());
                return;
            }

            // A l'ATM, le PIN verifie est celui de la carte du joueur (son compte perso est utilise au depart ; un patron peut
            // ensuite choisir celui d'une de ses entreprises). Au TPE, l'acheteur paie toujours depuis son compte perso.
            if (atmMenu != null) {
                BankManager.checkCardPin(player, atmMenu.getCardNumber(), atmMenu.getPersonalAccountNumber(), packet.getPin(), outcome -> {
                    boolean ok = outcome.check().isOk();
                    atmMenu.setPinValidated(ok);
                    // Un patron recoit la liste de ses entreprises pour choisir son compte ; les autres, une liste vide.
                    List<CompanyChoice> companies = ok
                            ? atmMenu.getCompanies().stream().limit(64).toList()
                            : List.of();
                    sendResponse(player, ok, outcome.balance().doubleValue(), outcome.check().message(), companies);
                });
            } else {
                BankManager.checkPin(player, packet.getPin(), outcome -> {
                    // Au TPE comme a la boutique, l'acheteur paie depuis son compte perso : meme verification.
                    if (tpeMenu != null) {
                        tpeMenu.setPinValidated(outcome.check().isOk());
                    } else {
                        shopMenu.setPinValidated(outcome.check().isOk());
                    }
                    sendResponse(player, outcome.check().isOk(), outcome.balance().doubleValue(), outcome.check().message(), List.of());
                });
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, double balance, String message,
                                     List<CompanyChoice> companies) {
        ModNetworking.CHANNEL.sendTo(
                new PinResponsePacket(success, balance, message, companies),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
