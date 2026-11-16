package org.bb.bb_economy.network;

import org.bb.bb_economy.Msg;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.CompanyChoice;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * Choix du compte a l'ATM, une fois le PIN valide : le compte personnel ({@link #PERSONAL}) ou celui d'une
 * entreprise dont le joueur est le patron. La reponse est un {@link AtmActionResponsePacket} avec le solde du
 * compte choisi.
 */
public class AtmSelectAccountPacket {

    /** Identifiant reserve pour "mon compte personnel" (les ids d'entreprise sont strictement positifs). */
    public static final int PERSONAL = 0;

    private final int companyId;

    public AtmSelectAccountPacket(int companyId) {
        this.companyId = companyId;
    }

    public static void encode(AtmSelectAccountPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.companyId);
    }

    public static AtmSelectAccountPacket decode(FriendlyByteBuf buf) {
        return new AtmSelectAccountPacket(buf.readInt());
    }

    public static void handle(AtmSelectAccountPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            process(player, packet);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void process(ServerPlayer player, AtmSelectAccountPacket packet) {
        if (!(player.containerMenu instanceof AtmScreenHandler atmMenu) || !atmMenu.stillValid(player)) {
            sendResponse(player, false, BigDecimal.ZERO, Msg.of(Msg.PREFIX + "atm.invalid_session"));
            return;
        }
        if (!atmMenu.isPinValidated()) {
            sendResponse(player, false, BigDecimal.ZERO, Msg.of(Msg.PREFIX + "atm.pin_not_valid"));
            return;
        }

        if (packet.companyId == PERSONAL) {
            String account = atmMenu.getPersonalAccountNumber();
            BankManager.loadBalance(player, account, balance -> {
                if (player.containerMenu != atmMenu) {
                    return;
                }
                atmMenu.useAccount(account);
                sendResponse(player, true, balance, Msg.of(Msg.PREFIX + "atm.personal_account"));
            });
            return;
        }

        // Une entreprise qui n'etait pas dans la liste proposee a l'ouverture est refusee sans meme aller en base.
        CompanyChoice company = atmMenu.findCompany(packet.companyId).orElse(null);
        if (company == null) {
            sendResponse(player, false, BigDecimal.ZERO, Msg.of(Msg.PREFIX + "atm.company_not_allowed"));
            return;
        }
        BankManager.selectCompanyAccount(player, company.id(), selection -> {
            if (player.containerMenu != atmMenu) {
                return;
            }
            if (selection.isEmpty()) {
                sendResponse(player, false, BigDecimal.ZERO, Msg.of(Msg.PREFIX + "atm.company_not_allowed"));
                return;
            }
            atmMenu.useAccount(selection.get().accountNumber());
            sendResponse(player, true, selection.get().balance(), Msg.of(Msg.PREFIX + "atm.company_account", company.name()));
        });
    }

    private static void sendResponse(ServerPlayer player, boolean success, BigDecimal balance, String message) {
        ModNetworking.CHANNEL.sendTo(
                new AtmActionResponsePacket(success, balance.doubleValue(), message),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
