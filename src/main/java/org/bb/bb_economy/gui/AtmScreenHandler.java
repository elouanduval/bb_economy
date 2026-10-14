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
    // Resolus une seule fois, cote serveur, a partir de la carte tenue en main a l'ouverture de l'ATM.
    // L'instance cree cote client (constructeur FriendlyByteBuf) n'en a pas besoin : rien ne les y lit.
    private final String cardNumber;
    private final String accountNumber;
    private boolean pinValidated;

    public AtmScreenHandler(int windowId, Inventory playerInventory, BlockPos pos, String cardNumber, String accountNumber) {
        super(ModMenus.ATM_MENU.get(), windowId);
        this.pos = pos;
        this.cardNumber = cardNumber;
        this.accountNumber = accountNumber;
    }

    public AtmScreenHandler(int windowId, Inventory inv, FriendlyByteBuf buf) {
        this(windowId, inv, buf.readBlockPos(), "", "");
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

    public String getCardNumber() {
        return cardNumber;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public boolean isPinValidated() {
        return pinValidated;
    }

    public void setPinValidated(boolean pinValidated) {
        this.pinValidated = pinValidated;
    }

    public static class Provider implements MenuProvider {
        private final BlockPos pos;
        private final String cardNumber;
        private final String accountNumber;

        public Provider(BlockPos pos, String cardNumber, String accountNumber) {
            this.pos = pos;
            this.cardNumber = cardNumber;
            this.accountNumber = accountNumber;
        }

        @Override
        public Component getDisplayName() {
            return Component.literal("ATM");
        }

        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
            return new AtmScreenHandler(windowId, playerInventory, pos, cardNumber, accountNumber);
        }
    }

    /** cardNumber/accountNumber sont resolus par l'appelant a partir de la carte tenue en main. */
    public static void open(Player player, BlockPos pos, String cardNumber, String accountNumber) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        NetworkHooks.openScreen(serverPlayer, new Provider(pos, cardNumber, accountNumber), buf -> buf.writeBlockPos(pos));
    }
}
