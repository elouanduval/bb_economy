package org.bb.bb_economy.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/**
 * Objets echanges dans les boutiques automatiques. Une offre designe un objet par son identifiant Minecraft
 * complet ({@code minecraft:oak_log}, {@code farmersdelight:...}).
 *
 * Seuls les objets "simples" (sans donnees NBT et non abimes) sont echanges : un outil enchante ou renomme
 * ne doit pas etre vendu au prix d'un objet neuf, ni etre retire par erreur de l'inventaire.
 */
public final class ShopItems {

    private ShopItems() {
    }

    /** Objet correspondant a l'identifiant d'une offre, vide s'il est inconnu (mod absent, faute de frappe). */
    public static Optional<Item> itemOf(String itemKey) {
        ResourceLocation id = itemKey == null ? null : ResourceLocation.tryParse(itemKey);
        if (id == null || !ForgeRegistries.ITEMS.containsKey(id)) {
            return Optional.empty();
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null || item == Items.AIR ? Optional.empty() : Optional.of(item);
    }

    /** Identifiant de l'objet tenu en main, pour creer une offre ; vide si la main est vide. */
    public static Optional<String> keyOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? Optional.empty() : Optional.of(id.toString());
    }

    private static boolean isPlain(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.getItem() == item && !stack.hasTag() && !stack.isDamaged();
    }

    public static int countPlain(Player player, Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (isPlain(stack, item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Retire {@code amount} objets simples de l'inventaire principal. L'appelant a verifie {@link #countPlain}. */
    public static void removePlain(Player player, Item item, int amount) {
        int remaining = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) {
                break;
            }
            if (isPlain(stack, item)) {
                int taken = Math.min(stack.getCount(), remaining);
                stack.shrink(taken);
                remaining -= taken;
            }
        }
    }

    /** Remet {@code amount} objets au joueur, par piles ; ce qui ne rentre pas tombe au sol. */
    public static void give(Player player, Item item, int amount) {
        int remaining = amount;
        int maxStack = Math.max(1, new ItemStack(item).getMaxStackSize());
        while (remaining > 0) {
            int size = Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(item, size);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= size;
        }
    }
}
