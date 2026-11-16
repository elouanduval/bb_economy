package org.bb.bb_economy.network;

import org.bb.bb_economy.Msg;
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
import java.util.OptionalInt;
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
                sendResponse(player, false, Msg.of(Msg.PREFIX + "tx.service_unavailable"), "0");
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void process(ServerPlayer player, TpeSetAmountPacket packet) {
        if (!(player.containerMenu instanceof TpeScreenHandler tpeMenu)
                || tpeMenu.getAccessMode() != TpeScreenHandler.AccessMode.SELLER
                || !tpeMenu.getPos().equals(packet.pos)
                || !tpeMenu.stillValid(player)) {
            sendResponse(player, false, Msg.of(Msg.PREFIX + "tpe.invalid_seller_session"), "0");
            return;
        }

        BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
        if (!(blockEntity instanceof TpeBlockEntity tpe)) {
            sendResponse(player, false, Msg.of(Msg.PREFIX + "tpe.not_found"), "0");
            return;
        }

        String pendingText = tpe.getPendingAmount().toPlainString();

        OptionalInt companyId = BankManager.parseCompanyId(tpe.getCompanyId());
        if (companyId.isEmpty()) {
            sendResponse(player, false, Msg.of(Msg.PREFIX + "tpe.no_company"), pendingText);
            return;
        }

        BigDecimal amount = Money.parsePositive(packet.amount).orElse(null);
        if (amount == null) {
            sendResponse(player, false,
                    Msg.of(Msg.PREFIX + "tpe.invalid_amount_max", Money.MAX_AMOUNT.toPlainString()),
                    pendingText);
            return;
        }

        // L'appartenance a l'entreprise est lue en base (hors thread principal) ; on revalide ensuite que la
        // session et le bloc sont toujours les memes avant de modifier le montant.
        BankManager.worksForCompany(player, companyId.getAsInt(), works -> {
            if (!works) {
                sendResponse(player, false, Msg.of(Msg.PREFIX + "tpe.not_authorized"), pendingText);
                return;
            }
            if (player.containerMenu != tpeMenu || !tpeMenu.stillValid(player)
                    || player.level().getBlockEntity(packet.pos) != tpe) {
                sendResponse(player, false, Msg.of(Msg.PREFIX + "tpe.invalid_seller_session"), "0");
                return;
            }
            tpe.setPendingAmount(amount);
            player.level().sendBlockUpdated(packet.pos, tpe.getBlockState(), tpe.getBlockState(), 3);

            String shown = amount.stripTrailingZeros().toPlainString();
            sendResponse(player, true, Msg.of(Msg.PREFIX + "tpe.amount_saved", shown + " " + Money.CURRENCY), amount.toPlainString());
        });
    }

    private static void sendResponse(ServerPlayer player, boolean success, String message, String amount) {
        ModNetworking.CHANNEL.sendTo(
                new TpeSetAmountResponsePacket(success, message, amount),
                player.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }
}
