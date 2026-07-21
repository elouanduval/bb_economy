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
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModMenus;

public class AtmScreenHandler extends AbstractContainerMenu {

    private final BlockPos pos;
    private boolean pinValidated;

    public AtmScreenHandler(int windowId, Inventory playerInventory, BlockPos pos) {
        super(ModMenus.ATM_MENU.get(), windowId);
        this.pos = pos;
    }

    public AtmScreenHandler(int windowId, Inventory inv, FriendlyByteBuf buf) {
        this(windowId, inv, buf.readBlockPos());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(pos).is(ModBlocks.ATM_BLOCK.get())
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    public boolean isPinValidated() {
        return pinValidated;
    }

    public void setPinValidated(boolean pinValidated) {
        this.pinValidated = pinValidated;
    }

    public static class Provider implements MenuProvider {
        private final BlockPos pos;

        public Provider(BlockPos pos) {
            this.pos = pos;
        }

        @Override
        public Component getDisplayName() {
            return Component.literal("ATM");
        }

        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
            return new AtmScreenHandler(windowId, playerInventory, pos);
        }
    }

    public static void open(Player player, BlockPos pos) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        NetworkHooks.openScreen(serverPlayer, new Provider(pos), buf -> buf.writeBlockPos(pos));
    }
}
