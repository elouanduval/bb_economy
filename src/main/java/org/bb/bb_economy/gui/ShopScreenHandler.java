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
import org.bb.bb_economy.database.ShopCatalog;
import org.bb.bb_economy.database.ShopManager;
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModMenus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ShopScreenHandler extends AbstractContainerMenu {

    /** Plafond de securite a la lecture cote client. */
    private static final int MAX_OFFERS = 512;

    private final BlockPos pos;
    private final int companyId;
    private final String companyName;
    private final List<ShopCatalog.Offer> offers;
    private boolean pinValidated;

    public ShopScreenHandler(int windowId, Inventory playerInventory, BlockPos pos, int companyId,
                             String companyName, List<ShopCatalog.Offer> offers) {
        super(ModMenus.SHOP_MENU.get(), windowId);
        this.pos = pos;
        this.companyId = companyId;
        this.companyName = companyName;
        this.offers = List.copyOf(offers);
    }

    public ShopScreenHandler(int windowId, Inventory inv, FriendlyByteBuf buf) {
        this(windowId, inv, buf.readBlockPos(), buf.readInt(), buf.readUtf(128), readOffers(buf));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(pos).is(ModBlocks.SHOP_BLOCK.get())
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getCompanyId() {
        return companyId;
    }

    public String getCompanyName() {
        return companyName;
    }

    public List<ShopCatalog.Offer> getOffers() {
        return offers;
    }

    public boolean isPinValidated() {
        return pinValidated;
    }

    public void setPinValidated(boolean pinValidated) {
        this.pinValidated = pinValidated;
    }

    // -------------------------------------------------------------------------
    // Transport des offres : id, objet, sens, taille du lot, prix du lot (le client n'a besoin de rien d'autre)
    // -------------------------------------------------------------------------

    private static void writeOffers(FriendlyByteBuf buf, List<ShopCatalog.Offer> offers) {
        buf.writeVarInt(offers.size());
        for (ShopCatalog.Offer offer : offers) {
            buf.writeInt(offer.id());
            buf.writeUtf(offer.itemKey(), 64);
            buf.writeByte(offer.direction().ordinal());
            buf.writeVarInt(offer.bundleSize());
            buf.writeUtf(offer.bundlePrice().toPlainString(), 32);
        }
    }

    private static List<ShopCatalog.Offer> readOffers(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_OFFERS);
        List<ShopCatalog.Offer> offers = new ArrayList<>(count);
        ShopCatalog.Direction[] directions = ShopCatalog.Direction.values();
        for (int i = 0; i < count; i++) {
            int id = buf.readInt();
            String itemKey = buf.readUtf(64);
            ShopCatalog.Direction direction = directions[Math.floorMod(buf.readByte(), directions.length)];
            int bundleSize = Math.max(1, buf.readVarInt());
            BigDecimal price = new BigDecimal(buf.readUtf(32));
            offers.add(new ShopCatalog.Offer(id, "", itemKey, direction, bundleSize, price, true));
        }
        return offers;
    }

    public static class Provider implements MenuProvider {
        private final BlockPos pos;
        private final int companyId;
        private final ShopManager.ShopView view;

        public Provider(BlockPos pos, int companyId, ShopManager.ShopView view) {
            this.pos = pos;
            this.companyId = companyId;
            this.view = view;
        }

        @Override
        public Component getDisplayName() {
            return Component.literal("Boutique");
        }

        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
            return new ShopScreenHandler(windowId, playerInventory, pos, companyId, view.companyName(), view.offers());
        }
    }

    public static void open(Player player, BlockPos pos, int companyId, ShopManager.ShopView view) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        NetworkHooks.openScreen(serverPlayer, new Provider(pos, companyId, view), buf -> {
            buf.writeBlockPos(pos);
            buf.writeInt(companyId);
            buf.writeUtf(view.companyName(), 128);
            writeOffers(buf, view.offers());
        });
    }
}
