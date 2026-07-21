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

public class TpeSetAmountPacket {

    private final BlockPos pos;
    private final String amount;

    public TpeSetAmountPacket(BlockPos pos, String amount) {
        this.pos = pos;
        this.amount = amount;
    }

    public static void encode(TpeSetAmountPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos);
        buf.writeUtf(packet.amount);
    }

    public static TpeSetAmountPacket decode(FriendlyByteBuf buf) {
        return new TpeSetAmountPacket(buf.readBlockPos(), buf.readUtf(64));
    }

    public static void handle(TpeSetAmountPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof TpeScreenHandler tpeMenu)
                    || tpeMenu.getAccessMode() != TpeScreenHandler.AccessMode.SELLER
                    || !tpeMenu.getPos().equals(packet.pos)
                    || !tpeMenu.stillValid(player)) {
                sendResponse(player, false, "Session TPE vendeur invalide.", "0");
                return;
            }

            BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
            if (!(blockEntity instanceof TpeBlockEntity tpeBlockEntity)) {
                sendResponse(player, false, "TPE introuvable.", "0");
                return;
            }

            if (tpeBlockEntity.getCompanyId().isBlank()) {
                sendResponse(player, false, "Ce TPE n'est lie a aucune entreprise.", tpeBlockEntity.getPendingAmount().toPlainString());
                return;
            }

            if (!BankManager.playerWorksForCompany(player, tpeBlockEntity.getCompanyId())) {
                sendResponse(player, false, "Vous n'etes pas autorise a utiliser ce TPE.", tpeBlockEntity.getPendingAmount().toPlainString());
                return;
            }

            BigDecimal amount;
            try {
                amount = new BigDecimal(packet.amount);
            } catch (NumberFormatException e) {
                sendResponse(player, false, "Montant invalide.", tpeBlockEntity.getPendingAmount().toPlainString());
                return;
            }

            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                sendResponse(player, false, "Le montant doit etre superieur a 0.", tpeBlockEntity.getPendingAmount().toPlainString());
                return;
            }

            if (amount.scale() > 2) {
                sendResponse(player, false, "Le montant ne peut pas avoir plus de 2 decimales.", tpeBlockEntity.getPendingAmount().toPlainString());
                return;
            }

            BigDecimal normalizedAmount = amount.stripTrailingZeros().scale() < 0
                    ? amount.setScale(0)
                    : amount.stripTrailingZeros();

            tpeBlockEntity.setPendingAmount(normalizedAmount);
            player.level().sendBlockUpdated(packet.pos, tpeBlockEntity.getBlockState(), tpeBlockEntity.getBlockState(), 3);

            sendResponse(player, true, "Montant enregistre. Le client pourra payer " + normalizedAmount.toPlainString() + " EUR.", normalizedAmount.toPlainString());
        });
        ctx.get().setPacketHandled(true);
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message, String amount) {
        ModNetworking.CHANNEL.sendTo(
                new TpeSetAmountResponsePacket(success, message, amount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
