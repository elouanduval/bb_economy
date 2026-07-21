package org.bb.bb_economy.init;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.bb.bb_economy.BB_Economy;
import org.bb.bb_economy.blocks.entity.TpeBlockEntity;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BB_Economy.MODID);

    public static final RegistryObject<BlockEntityType<TpeBlockEntity>> TPE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("tpe",
                    () -> BlockEntityType.Builder.of(TpeBlockEntity::new, ModBlocks.TPE_BLOCK.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
