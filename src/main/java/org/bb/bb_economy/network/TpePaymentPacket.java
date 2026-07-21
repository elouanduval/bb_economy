package org.bb.bb_economy.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import org.bb.bb_economy.blocks.entity.TpeBlockEntity;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;

import java.math.BigDecimal;
import java.util.function.Supplier;

public class TpePaymentPacket {

    private final BlockPos pos;

    public TpePaymentPacket(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(TpePaymentPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
    }

    public static TpePaymentPacket decode(FriendlyByteBuf buf) {
        return new TpePaymentPacket(buf.readBlockPos());
    }

    public static void handle(TpePaymentPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
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
            if (!(blockEntity instanceof TpeBlockEntity tpeBlockEntity)) {
                sendResponse(player, false, "TPE introuvable.", "0");
                return;
            }

            if (!BankManager.hasOwnedCardInHand(player)) {
                sendResponse(player, false, "Vous devez garder votre carte bancaire en main pour payer.", tpeBlockEntity.getPendingAmount().toPlainString());
                return;
            }

            BigDecimal pendingAmount = tpeBlockEntity.getPendingAmount();
            if (pendingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                sendResponse(player, false, "Aucun montant n'est en attente sur ce TPE.", "0");
                return;
            }

            if (tpeBlockEntity.getCompanyAccount().isBlank()) {
                sendResponse(player, false, "Compte entreprise introuvable.", pendingAmount.toPlainString());
                return;
            }

            boolean success = BankManager.processTpePayment(player, tpeBlockEntity.getCompanyAccount(), pendingAmount);
            if (!success) {
                sendResponse(player, false, "Paiement refuse : solde insuffisant ou compte invalide.", pendingAmount.toPlainString());
                return;
            }

            tpeBlockEntity.setPendingAmount(BigDecimal.ZERO);
            player.level().sendBlockUpdated(packet.pos, tpeBlockEntity.getBlockState(), tpeBlockEntity.getBlockState(), 3);
            sendResponse(player, true, "Paiement effectue.", "0");
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message, String amount) {
        ModNetworking.CHANNEL.sendTo(
                new TpePaymentResponsePacket(success, message, amount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
