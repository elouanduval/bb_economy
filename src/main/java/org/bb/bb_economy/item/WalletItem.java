package org.bb.bb_economy.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.bb.bb_economy.Config;
import org.bb.bb_economy.init.ModItems;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WalletItem extends Item {

    private static final String TAG_STORED_MONEY = "StoredMoney";

    public WalletItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack wallet = player.getItemInHand(hand);

        if (player.isShiftKeyDown()) {
            int extracted = withdrawMoney(player, wallet);
            if (!level.isClientSide) {
                if (extracted > 0) {
                    player.displayClientMessage(Component.literal(extracted + " billet(s) retire(s) du portefeuille."), true);
                } else {
                    player.displayClientMessage(Component.literal("Le portefeuille est vide."), true);
                }
            }
            return InteractionResultHolder.sidedSuccess(wallet, level.isClientSide);
        }

        int stored = depositMoney(player, wallet);
        if (!level.isClientSide) {
            if (stored > 0) {
                player.displayClientMessage(Component.literal(stored + " billet(s) range(s) dans le portefeuille."), true);
            } else {
                player.displayClientMessage(Component.literal("Aucun billet a ranger."), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(wallet, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.literal("Billets : " + getStoredMoney(stack) + " / " + getMaxStoredMoney()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Clic droit : ranger les billets").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal("Sneak + clic droit : retirer des billets").withStyle(ChatFormatting.DARK_GRAY));
    }

    public static int getStoredMoney(ItemStack stack) {
        if (!stack.hasTag() || stack.getTag() == null) {
            return 0;
        }
        return stack.getTag().getInt(TAG_STORED_MONEY);
    }

    private static void setStoredMoney(ItemStack stack, int amount) {
        stack.getOrCreateTag().putInt(TAG_STORED_MONEY, Math.max(0, Math.min(getMaxStoredMoney(), amount)));
    }

    private int depositMoney(Player player, ItemStack wallet) {
        int current = getStoredMoney(wallet);
        int maxStoredMoney = getMaxStoredMoney();
        if (current >= maxStoredMoney) {
            return 0;
        }

        int capacity = maxStoredMoney - current;
        int moved = 0;

        for (ItemStack stack : player.getInventory().items) {
            if (stack == wallet || stack.getItem() != ModItems.MONEY.get()) {
                continue;
            }

            int taken = Math.min(stack.getCount(), capacity);
            if (taken <= 0) {
                break;
            }

            stack.shrink(taken);
            capacity -= taken;
            moved += taken;

            if (capacity <= 0) {
                break;
            }
        }

        if (moved > 0) {
            setStoredMoney(wallet, current + moved);
        }
        return moved;
    }

    private int withdrawMoney(Player player, ItemStack wallet) {
        int current = getStoredMoney(wallet);
        if (current <= 0) {
            return 0;
        }

        int toExtract = Math.min(64, current);
        ItemStack moneyStack = new ItemStack(ModItems.MONEY.get(), toExtract);
        boolean added = player.getInventory().add(moneyStack);

        int moved = toExtract;
        if (!added) {
            int remainder = moneyStack.getCount();
            moved = toExtract - remainder;
        }

        if (moved > 0) {
            setStoredMoney(wallet, current - moved);
        }

        return moved;
    }

    private static int getMaxStoredMoney() {
        return Math.max(1, Config.WALLET_MAX_STORED_MONEY);
    }
}
