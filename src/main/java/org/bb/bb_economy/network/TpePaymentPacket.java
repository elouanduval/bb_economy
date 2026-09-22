package org.bb.bb_economy.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.blocks.entity.TpeBlockEntity;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.Money;
import org.bb.bb_economy.database.TxResult;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.util.function.Supplier;

public class TpePaymentPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final BlockPos pos;
    private final String expectedAmount;

    /**
     * @param expectedAmount montant affiche a l'acheteur. Le serveur refuse le paiement si le vendeur
     *                       a change le montant entre-temps : on ne debite jamais plus que ce que le client a vu.
     */
    public TpePaymentPacket(BlockPos pos, String expectedAmount) {
        this.pos = pos;
        this.expectedAmount = expectedAmount;
    }

    public static void encode(TpePaymentPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.expectedAmount);
    }

    public static TpePaymentPacket decode(FriendlyByteBuf buf) {
        return new TpePaymentPacket(buf.readBlockPos(), buf.readUtf(64));
    }

    public static void handle(TpePaymentPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            try {
                process(player, packet);
            } catch (RuntimeException e) {
                LOGGER.error("Erreur pendant un paiement TPE de {}", player.getName().getString(), e);
                sendResponse(player, false, "Service bancaire indisponible. Reessayez plus tard.", "0");
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void process(ServerPlayer player, TpePaymentPacket packet) {
        if (!(player.containerMenu instanceof TpeScreenHandler tpeMenu)
                || tpeMenu.getAccessMode() != TpeScreenHandler.AccessMode.BUYER
                || !tpeMenu.getPos().equals(packet.pos)
                || !tpeMenu.stillValid(player)) {
            sendResponse(player, false, "Session TPE client invalide.", "0");
            return;
        }
        if (!tpeMenu.isPinValidated()) {
            sendResponse(player, false, "PIN incorrect.", "0");
            return;
        }

        BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
        if (!(blockEntity instanceof TpeBlockEntity tpe)) {
            sendResponse(player, false, "TPE introuvable.", "0");
            return;
        }

        BigDecimal pendingAmount = tpe.getPendingAmount();
        String pendingText = pendingAmount.toPlainString();

        if (!BankManager.hasOwnedCardInHand(player)) {
            sendResponse(player, false, "Vous devez garder votre carte bancaire en main pour payer.", pendingText);
            return;
        }
        if (pendingAmount.signum() <= 0) {
            sendResponse(player, false, "Aucun montant n'est en attente sur ce TPE.", "0");
            return;
        }

        BigDecimal expected = Money.parsePositive(packet.expectedAmount).orElse(null);
        if (expected == null || expected.compareTo(pendingAmount) != 0) {
            sendResponse(player, false, "Le montant a change. Verifiez-le puis validez a nouveau.", pendingText);
            return;
        }

        if (tpe.getCompanyId().isBlank() || !BankManager.companyExists(tpe.getCompanyId())) {
            sendResponse(player, false, "Compte entreprise introuvable.", pendingText);
            return;
        }
        // Le compte est relu en base a chaque paiement : les donnees du bloc ne sont qu'un raccourci d'affichage.
        String companyAccount = BankManager.getCompanyAccountNumber(tpe.getCompanyId());

        TxResult result = BankManager.processTpePayment(player, companyAccount, pendingAmount);
        if (!result.isOk()) {
            String message = switch (result) {
                case INSUFFICIENT_FUNDS -> "Paiement refuse : solde insuffisant.";
                case SAME_ACCOUNT -> "Vous ne pouvez pas payer votre propre compte.";
                default -> "Paiement refuse : " + result.message();
            };
            sendResponse(player, false, message, pendingText);
            return;
        }

        tpe.setPendingAmount(BigDecimal.ZERO);
        tpeMenu.setPinValidated(false);
        player.level().sendBlockUpdated(packet.pos, tpe.getBlockState(), tpe.getBlockState(), 3);
        sendResponse(player, true, "Paiement effectue.", "0");
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message, String amount) {
        ModNetworking.CHANNEL.sendTo(
                new TpePaymentResponsePacket(success, message, amount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
