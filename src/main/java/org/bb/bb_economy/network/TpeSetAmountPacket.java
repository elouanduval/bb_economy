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
import org.bb.bb_economy.gui.TpeScreenHandler;
import org.bb.bb_economy.init.ModNetworking;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.util.function.Supplier;

public class TpeSetAmountPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

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
            try {
                process(player, packet);
            } catch (RuntimeException e) {
                LOGGER.error("Erreur pendant la definition d'un montant TPE par {}", player.getName().getString(), e);
                sendResponse(player, false, "Service bancaire indisponible. Reessayez plus tard.", "0");
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void process(ServerPlayer player, TpeSetAmountPacket packet) {
        if (!(player.containerMenu instanceof TpeScreenHandler tpeMenu)
                || tpeMenu.getAccessMode() != TpeScreenHandler.AccessMode.SELLER
                || !tpeMenu.getPos().equals(packet.pos)
                || !tpeMenu.stillValid(player)) {
            sendResponse(player, false, "Session TPE vendeur invalide.", "0");
            return;
        }

        BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
        if (!(blockEntity instanceof TpeBlockEntity tpe)) {
            sendResponse(player, false, "TPE introuvable.", "0");
            return;
        }

        String pendingText = tpe.getPendingAmount().toPlainString();

        if (tpe.getCompanyId().isBlank()) {
            sendResponse(player, false, "Ce TPE n'est lie a aucune entreprise.", pendingText);
            return;
        }
        if (!BankManager.playerWorksForCompany(player, tpe.getCompanyId())) {
            sendResponse(player, false, "Vous n'etes pas autorise a utiliser ce TPE.", pendingText);
            return;
        }

        BigDecimal amount = Money.parsePositive(packet.amount).orElse(null);
        if (amount == null) {
            sendResponse(player, false,
                    "Montant invalide (superieur a 0, 2 decimales maximum, jusqu'a " + Money.MAX_AMOUNT.toPlainString() + ").",
                    pendingText);
            return;
        }

        tpe.setPendingAmount(amount);
        player.level().sendBlockUpdated(packet.pos, tpe.getBlockState(), tpe.getBlockState(), 3);

        String shown = amount.stripTrailingZeros().toPlainString();
        sendResponse(player, true, "Montant enregistre. Le client pourra payer " + shown + " EUR.", amount.toPlainString());
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message, String amount) {
        ModNetworking.CHANNEL.sendTo(
                new TpeSetAmountResponsePacket(success, message, amount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
