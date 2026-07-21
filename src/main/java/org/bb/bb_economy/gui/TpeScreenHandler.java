package org.bb.bb_economy.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;
import org.bb.bb_economy.blocks.entity.TpeBlockEntity;
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModMenus;

import java.math.BigDecimal;

public class TpeScreenHandler extends AbstractContainerMenu {

    public enum AccessMode {
        SELLER,
        BUYER
    }

    private final BlockPos pos;
    private final String companyId;
    private final String companyAccount;
    private final BigDecimal pendingAmount;
    private final AccessMode accessMode;
    private boolean pinValidated;

    public TpeScreenHandler(int windowId, Inventory playerInventory, BlockPos pos, String companyId,
                            String companyAccount, BigDecimal pendingAmount, AccessMode accessMode) {
        super(ModMenus.TPE_MENU.get(), windowId);
        this.pos = pos;
        this.companyId = companyId;
        this.companyAccount = companyAccount;
        this.pendingAmount = pendingAmount;
        this.accessMode = accessMode;
    }

    public TpeScreenHandler(int windowId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(
                windowId,
                playerInventory,
                buf.readBlockPos(),
                buf.readUtf(64),
                buf.readUtf(64),
                new BigDecimal(buf.readUtf(64)),
                buf.readEnum(AccessMode.class)
        );
    }

    @Override
    public ItemStack quickMoveStack(Player player, int i) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(pos).is(ModBlocks.TPE_BLOCK.get())
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    public BlockPos getPos() {
        return pos;
    }

    public String getCompanyId() {
        return companyId;
    }

    public String getCompanyAccount() {
        return companyAccount;
    }

    public BigDecimal getPendingAmount() {
        return pendingAmount;
    }

    public AccessMode getAccessMode() {
        return accessMode;
    }

    public boolean isPinValidated() {
        return pinValidated;
    }

    public void setPinValidated(boolean pinValidated) {
        this.pinValidated = pinValidated;
    }

    public static class Provider implements MenuProvider {
        private final BlockPos pos;
        private final String companyId;
        private final String companyAccount;
        private final BigDecimal pendingAmount;
        private final AccessMode accessMode;

        public Provider(BlockPos pos, String companyId, String companyAccount, BigDecimal pendingAmount, AccessMode accessMode) {
            this.pos = pos;
            this.companyId = companyId;
            this.companyAccount = companyAccount;
            this.pendingAmount = pendingAmount;
            this.accessMode = accessMode;
        }

        @Override
        public Component getDisplayName() {
            return Component.literal("TPE");
        }

        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
            return new TpeScreenHandler(windowId, playerInventory, pos, companyId, companyAccount, pendingAmount, accessMode);
        }
    }

    public static void open(Player player, BlockPos pos, TpeBlockEntity tpeBlockEntity, AccessMode accessMode) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        String companyId = tpeBlockEntity.getCompanyId();
        String companyAccount = tpeBlockEntity.getCompanyAccount();
        String pendingAmount = tpeBlockEntity.getPendingAmount().toPlainString();

        NetworkHooks.openScreen(serverPlayer, new Provider(pos, companyId, companyAccount, tpeBlockEntity.getPendingAmount(), accessMode), buf -> {
            buf.writeBlockPos(pos);
            buf.writeUtf(companyId);
            buf.writeUtf(companyAccount);
            buf.writeUtf(pendingAmount);
            buf.writeEnum(accessMode);
        });
    }
}
