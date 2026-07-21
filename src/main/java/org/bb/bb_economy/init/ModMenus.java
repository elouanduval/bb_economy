package org.bb.bb_economy.init;

import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.bb.bb_economy.BB_Economy;
import org.bb.bb_economy.gui.AtmScreenHandler;
import org.bb.bb_economy.gui.TpeScreenHandler;

public class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, BB_Economy.MODID);

    public static final RegistryObject<MenuType<AtmScreenHandler>> ATM_MENU =
            MENUS.register("atm_menu",
                    () -> IForgeMenuType.create(AtmScreenHandler::new));

    public static final RegistryObject<MenuType<TpeScreenHandler>> TPE_MENU =
            MENUS.register("tpe_menu",
                    () -> IForgeMenuType.create(TpeScreenHandler::new));
}
