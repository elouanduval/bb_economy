package org.bb.bb_economy.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.blocks.entity.TpeBlockEntity;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.Money;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.math.BigDecimal;
import java.util.function.Supplier;

public class TpePaymentPacket {

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
            process(player, packet);
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

        if (pendingAmount.signum() <= 0) {
            sendResponse(player, false, "Aucun montant n'est en attente sur ce TPE.", "0");
            return;
        }
        BigDecimal expected = Money.parsePositive(packet.expectedAmount).orElse(null);
        if (expected == null || expected.compareTo(pendingAmount) != 0) {
            sendResponse(player, false, "Le montant a change. Verifiez-le puis validez a nouveau.", pendingText);
            return;
        }
        String companyId = tpe.getCompanyId();
        if (companyId.isBlank()) {
            sendResponse(player, false, "Compte entreprise introuvable.", pendingText);
            return;
        }

        BankManager.HandCards cards = BankManager.cardsInHand(player);
        // Carte en main, compte de l'entreprise et paiement sont verifies/executes dans le meme aller-retour
        // base (voir BankManager.payAtTpe) : les donnees du bloc ne sont qu'un raccourci d'affichage.
        BankManager.payAtTpe(player, cards, companyId, pendingAmount, outcome -> {
            if (outcome.outcome() != BankManager.TpeOutcome.OK) {
                sendResponse(player, false, outcome.message(), pendingText);
                return;
            }
            tpe.setPendingAmount(BigDecimal.ZERO);
            tpeMenu.setPinValidated(false);
            player.level().sendBlockUpdated(packet.pos, tpe.getBlockState(), tpe.getBlockState(), 3);
            sendResponse(player, true, "Paiement effectue.", "0");
        });
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message, String amount) {
        ModNetworking.CHANNEL.sendTo(
                new TpePaymentResponsePacket(success, message, amount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
