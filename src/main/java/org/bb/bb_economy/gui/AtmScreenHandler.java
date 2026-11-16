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
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.CompanyChoice;
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModMenus;

import java.util.List;
import java.util.Optional;

public class AtmScreenHandler extends AbstractContainerMenu {

    private final BlockPos pos;
    // Resolus une seule fois, cote serveur, a partir de la carte tenue en main a l'ouverture de l'ATM.
    // L'instance cree cote client (constructeur FriendlyByteBuf) n'en a pas besoin : rien ne les y lit.
    private final String cardNumber;
    private final String personalAccountNumber;
    private final List<CompanyChoice> companies;
    // Compte sur lequel portent depot, retrait et virement : le compte personnel au depart, ou celui d'une
    // entreprise dont le joueur est le patron, une fois choisi. Modifie uniquement sur le thread principal.
    private String activeAccountNumber;
    private boolean pinValidated;

    public AtmScreenHandler(int windowId, Inventory playerInventory, BlockPos pos, String cardNumber,
                            String personalAccountNumber, List<CompanyChoice> companies) {
        super(ModMenus.ATM_MENU.get(), windowId);
        this.pos = pos;
        this.cardNumber = cardNumber;
        this.personalAccountNumber = personalAccountNumber;
        this.companies = List.copyOf(companies);
        this.activeAccountNumber = personalAccountNumber;
    }

    public AtmScreenHandler(int windowId, Inventory inv, FriendlyByteBuf buf) {
        this(windowId, inv, buf.readBlockPos(), "", "", List.of());
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

    public String getPersonalAccountNumber() {
        return personalAccountNumber;
    }

    /** Compte actuellement utilise par les operations (personnel ou entreprise). */
    public String getAccountNumber() {
        return activeAccountNumber;
    }

    public void useAccount(String accountNumber) {
        this.activeAccountNumber = accountNumber;
    }

    /** Entreprises proposees au patron a l'ouverture (a reverifier en base avant d'en utiliser une). */
    public List<CompanyChoice> getCompanies() {
        return companies;
    }

    public Optional<CompanyChoice> findCompany(int companyId) {
        return companies.stream().filter(company -> company.id() == companyId).findFirst();
    }

    public boolean isPinValidated() {
        return pinValidated;
    }

    /** Une validation du PIN remet toujours l'ATM sur le compte personnel. */
    public void setPinValidated(boolean pinValidated) {
        this.pinValidated = pinValidated;
        this.activeAccountNumber = personalAccountNumber;
    }

    public static class Provider implements MenuProvider {
        private final BlockPos pos;
        private final String cardNumber;
        private final String personalAccountNumber;
        private final List<CompanyChoice> companies;

        public Provider(BlockPos pos, String cardNumber, String personalAccountNumber,
                        List<CompanyChoice> companies) {
            this.pos = pos;
            this.cardNumber = cardNumber;
            this.personalAccountNumber = personalAccountNumber;
            this.companies = companies;
        }

        @Override
        public Component getDisplayName() {
            return Component.literal("ATM");
        }

        @Override
        public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
            return new AtmScreenHandler(windowId, playerInventory, pos, cardNumber, personalAccountNumber, companies);
        }
    }

    public static void open(Player player, BlockPos pos, BankManager.AtmSession session) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        NetworkHooks.openScreen(
                serverPlayer,
                new Provider(pos, session.cardNumber(), session.personalAccountNumber(), session.companies()),
                buf -> buf.writeBlockPos(pos));
    }
}
