package org.bb.bb_economy.init;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.bb.bb_economy.blocks.AtmBlock;
import org.bb.bb_economy.BB_Economy;
import org.bb.bb_economy.blocks.TpeBlock;
import org.bb.bb_economy.item.TpeItem;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BB_Economy.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BB_Economy.MODID);

    public static final RegistryObject<Block> ATM_BLOCK = BLOCKS.register("atm",
            () -> new AtmBlock(BlockBehaviour.Properties.of().strength(3.5f)));

    public static final RegistryObject<Item> ATM_BLOCK_ITEM = ITEMS.register("atm",
            () -> new BlockItem(ATM_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<Block> TPE_BLOCK = BLOCKS.register("tpe",
            () -> new TpeBlock(BlockBehaviour.Properties.of().strength(2.5f).noOcclusion()));

    public static final RegistryObject<Item> TPE_BLOCK_ITEM = ITEMS.register("tpe",
            () -> new TpeItem(TPE_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
    }

}
