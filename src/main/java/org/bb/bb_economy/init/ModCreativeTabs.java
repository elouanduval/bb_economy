package org.bb.bb_economy.init;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import org.bb.bb_economy.BB_Economy;

/**
 * Onglet du mode creatif (et donc des listes comme JEI, qui s'appuient dessus) regroupant les objets du mod.
 * La carte bancaire n'y est pas : une carte vierge ne correspond a aucun compte et ne servirait a rien
 * (voir /bb_economy card regive). Le TPE et la boutique y sont non lies : on les lie avec /bb_economy give.
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BB_Economy.MODID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + BB_Economy.MODID))
            .icon(() -> new ItemStack(ModBlocks.ATM_BLOCK_ITEM.get()))
            .displayItems((parameters, output) -> {
                output.accept(ModBlocks.ATM_BLOCK_ITEM.get());
                output.accept(ModBlocks.TPE_BLOCK_ITEM.get());
                output.accept(ModBlocks.SHOP_BLOCK_ITEM.get());
                output.accept(ModItems.MONEY.get());
                output.accept(ModItems.WALLET.get());
            })
            .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus eventBus) {
        TABS.register(eventBus);
    }
}
