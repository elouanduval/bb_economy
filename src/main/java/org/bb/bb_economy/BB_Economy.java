package org.bb.bb_economy;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.bb.bb_economy.client.ClientSetup;
import org.bb.bb_economy.command.AdminCommands;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.DatabaseManager;
import org.bb.bb_economy.database.DbExecutor;
import org.bb.bb_economy.init.ModBlockEntities;
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModItems;
import org.bb.bb_economy.init.ModMenus;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.network.OpenPinSetupPacket;

import org.slf4j.Logger;


@Mod(BB_Economy.MODID)
public class BB_Economy {

    public static final String MODID = "bb_economy";

    private static final Logger LOGGER = LogUtils.getLogger();

    public BB_Economy() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModNetworking.register();

        // COMMON et non SERVER : le fichier est dans config/ (facile a trouver), pas dans le dossier du monde.
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "bb_economy.toml");

        MinecraftForge.EVENT_BUS.register(new ForgeEvents());

        ClientSetup.init();

        LOGGER.info("Mod charge.");
    }

    public static class ForgeEvents {

        @SubscribeEvent
        public void onServerStarting(ServerStartingEvent event) {
            DatabaseManager.init();
            LOGGER.info("BDD initialisee.");
        }

        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            // On arrete d'abord d'accepter de nouvelles requetes en arriere-plan (celles deja en vol finissent),
            // puis on ferme le pool de connexions.
            DbExecutor.shutdown();
            DatabaseManager.close();
        }

        @SubscribeEvent
        public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            Player player = event.getEntity();
            if (!player.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
                // Une base indisponible ne doit jamais empecher un joueur de se connecter.
                try {
                    // Verifie si le joueur a un profil actif et une carte avec PIN par defaut
                    if (BankManager.hasActiveProfile(player) && BankManager.hasDefaultCardPin(player)) {
                        player.displayClientMessage(
                                Component.translatable(Msg.PREFIX + "pin.setup_prompt"),
                                false
                        );
                        ModNetworking.CHANNEL.sendTo(
                                new OpenPinSetupPacket(),
                                serverPlayer.connection.connection,
                                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                        );
                    }
                } catch (RuntimeException e) {
                    LOGGER.error("Verification du PIN a la connexion impossible pour {}", player.getName().getString(), e);
                }
            }
        }

        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            AdminCommands.register(event.getDispatcher());
        }
    }
}
