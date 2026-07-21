package org.bb.bb_economy.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.bb.bb_economy.BB_Economy;
import org.bb.bb_economy.gui.AtmScreen;
import org.bb.bb_economy.gui.TpeScreen;
import org.bb.bb_economy.init.ModMenus;

@Mod.EventBusSubscriber(modid = BB_Economy.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetup {

    public static void init() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientSetup::clientSetup);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        MenuScreens.register(ModMenus.ATM_MENU.get(), AtmScreen::new);
        MenuScreens.register(ModMenus.TPE_MENU.get(), TpeScreen::new);
    }
}
