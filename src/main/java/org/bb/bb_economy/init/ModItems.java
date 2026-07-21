package org.bb.bb_economy.init;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.eventbus.api.IEventBus;
import org.bb.bb_economy.BB_Economy;
import org.bb.bb_economy.item.CardItem;
import org.bb.bb_economy.item.WalletItem;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BB_Economy.MODID);

    public static final RegistryObject<Item> MONEY =
            ITEMS.register("money", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> CARD =
            ITEMS.register("card", () -> new CardItem(new Item.Properties()));

    public static final RegistryObject<Item> WALLET =
            ITEMS.register("wallet", () -> new WalletItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
