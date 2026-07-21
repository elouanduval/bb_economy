package org.bb.bb_economy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.DatabaseManager;
import org.bb.bb_economy.database.SalaryScheduler;
import org.bb.bb_economy.init.ModBlockEntities;
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModItems;
import org.bb.bb_economy.init.ModMenus;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.item.TpeItem;
import org.bb.bb_economy.network.OpenPinSetupPacket;

import java.util.List;

@Mod(BB_Economy.MODID)
public class BB_Economy {

    public static final String MODID = "bb_economy";

    public BB_Economy() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModNetworking.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        MinecraftForge.EVENT_BUS.register(new ForgeEvents());
        MinecraftForge.EVENT_BUS.register(new SalaryScheduler());

        ClientSetup.init();

        System.out.println("[BB Economy] Mod charge !");
    }

    public static class ForgeEvents {

        private static final List<String> ADMIN_COMMANDS = List.of(
                "/bb_economy help - Affiche l'aide des commandes administrateur du mod",
                "/bb_economy bank create <joueur> - Cree un compte bancaire et une carte pour le joueur",
                "/bb_economy bank reissue_card <joueur> - Redonne au joueur sa carte bancaire en reinitialisant le PIN",
                "/bb_economy card regive <joueur> - Redonne au joueur sa carte bancaire liee a son compte",
                "/give <joueur> bb_economy:card - Donne une carte bancaire item",
                "/give <joueur> bb_economy:money <quantite> - Donne des billets",
                "/give <joueur> bb_economy:wallet - Donne un portefeuille",
                "/give <joueur> bb_economy:atm - Donne un distributeur ATM",
                "/bb_economy give tpe <company_id> [joueur] - Donne un TPE lie a une entreprise"
        );

        @SubscribeEvent
        public void onServerStarting(ServerStartingEvent event) {
            DatabaseManager.init();
            System.out.println("[BB Economy] BDD initialisee.");
        }

        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            DatabaseManager.close();
        }

        @SubscribeEvent
        public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            Player player = event.getEntity();
            if (!player.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {

                // Vérifie si le joueur a un profil actif et une carte avec PIN par défaut
                if (BankManager.hasActiveProfile(player) && BankManager.hasDefaultCardPin(player)) {
                    player.displayClientMessage(
                            Component.literal("Definissez maintenant le code PIN de votre carte bancaire."),
                            false
                    );
                    ModNetworking.CHANNEL.sendTo(
                            new OpenPinSetupPacket(),
                            serverPlayer.connection.connection,
                            net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );
                }
            }
        }

        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            registerCommands(event.getDispatcher());
        }

        private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
            dispatcher.register(Commands.literal("bb_economy")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("help")
                            .executes(ctx -> showAdminHelp(ctx.getSource())))
                    .then(Commands.literal("bank")
                            .then(Commands.literal("create")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> createPlayerBankAccount(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            ))))
                            .then(Commands.literal("reissue_card")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> reissuePlayerCard(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            )))))
                    .then(Commands.literal("card")
                            .then(Commands.literal("regive")
                                    .then(Commands.argument("player", EntityArgument.player())
                                            .executes(ctx -> regivePlayerCard(
                                                    ctx.getSource(),
                                                    EntityArgument.getPlayer(ctx, "player")
                                            )))))
                    .then(Commands.literal("give")
                            .then(Commands.literal("tpe")
                                    .then(Commands.argument("company_id", StringArgumentType.word())
                                            .executes(ctx -> giveCompanyTpe(
                                                    ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "company_id"),
                                                    null
                                            ))
                                            .then(Commands.argument("player", EntityArgument.player())
                                                    .executes(ctx -> giveCompanyTpe(
                                                            ctx.getSource(),
                                                            StringArgumentType.getString(ctx, "company_id"),
                                                            EntityArgument.getPlayer(ctx, "player")
                                                    )))))));
        }

        private int showAdminHelp(CommandSourceStack source) {
            source.sendSuccess(() -> Component.literal("BB Economy - Commandes administrateur :"), false);
            for (String command : ADMIN_COMMANDS) {
                source.sendSuccess(() -> Component.literal(" - " + command), false);
            }
            return 1;
        }

        private int regivePlayerCard(CommandSourceStack source, ServerPlayer target) {
            final String cardNumber;
            try {
                cardNumber = BankManager.getCardNumber(target);
            } catch (RuntimeException e) {
                source.sendFailure(Component.literal("Aucune carte bancaire n'est associee a ce joueur."));
                return 0;
            }

            if (!BankManager.giveCardItemByNumber(target, cardNumber)) {
                source.sendFailure(Component.literal("Impossible de redonner la carte bancaire."));
                return 0;
            }

            source.sendSuccess(
                    () -> Component.literal("Carte " + cardNumber + " redonnee a " + target.getName().getString() + "."),
                    true
            );
            target.displayClientMessage(
                    Component.literal("Votre carte bancaire (" + cardNumber + ") vous a ete redonnee."),
                    false
            );
            return 1;
        }

        private int createPlayerBankAccount(CommandSourceStack source, ServerPlayer target) {
            final String cardNumber;
            try {
                cardNumber = BankManager.createBankAccountAndCard(target);
            } catch (IllegalStateException e) {
                source.sendFailure(Component.literal("Le joueur possede deja un compte bancaire."));
                return 0;
            } catch (RuntimeException e) {
                source.sendFailure(Component.literal("Creation du compte impossible : " + e.getMessage()));
                return 0;
            }

            ModNetworking.CHANNEL.sendTo(
                    new OpenPinSetupPacket(),
                    target.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );

            source.sendSuccess(
                    () -> Component.literal("Compte bancaire et carte " + cardNumber + " crees pour " + target.getName().getString() + "."),
                    true
            );
            target.displayClientMessage(
                    Component.literal("Votre compte bancaire a ete cree. Votre carte (" + cardNumber + ") vous a ete remise. Choisissez maintenant un PIN."),
                    false
            );
            return 1;
        }

        private int reissuePlayerCard(CommandSourceStack source, ServerPlayer target) {
            final String cardNumber;
            try {
                cardNumber = BankManager.reissueCard(target);
            } catch (RuntimeException e) {
                source.sendFailure(Component.literal("Reemission de carte impossible : " + e.getMessage()));
                return 0;
            }

            ModNetworking.CHANNEL.sendTo(
                    new OpenPinSetupPacket(),
                    target.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
            );

            source.sendSuccess(
                    () -> Component.literal("Carte " + cardNumber + " redonnee a " + target.getName().getString() + "."),
                    true
            );
            target.displayClientMessage(
                    Component.literal("Votre carte bancaire (" + cardNumber + ") vous a ete redonnee. Choisissez maintenant un nouveau PIN."),
                    false
            );
            return 1;
        }

        private int giveCompanyTpe(CommandSourceStack source, String rawCompanyId, ServerPlayer explicitTarget) {
            final String companyId;
            try {
                companyId = BankManager.normalizeCompanyId(rawCompanyId);
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal("Identifiant d'entreprise invalide."));
                return 0;
            }

            if (!BankManager.companyExists(companyId)) {
                source.sendFailure(Component.literal("Entreprise introuvable : " + companyId));
                return 0;
            }

            String companyAccount = BankManager.getCompanyAccountNumber(companyId);

            ServerPlayer target = explicitTarget;
            if (target == null) {
                try {
                    target = source.getPlayerOrException();
                } catch (Exception e) {
                    source.sendFailure(Component.literal("Precisez un joueur cible depuis la console."));
                    return 0;
                }
            }

            ItemStack stack = new ItemStack(ModBlocks.TPE_BLOCK_ITEM.get());
            TpeItem.setCompanyData(stack, companyId, companyAccount);

            boolean added = target.getInventory().add(stack);
            if (!added) {
                target.drop(stack, false);
            }

            ServerPlayer finalTarget = target;
            source.sendSuccess(
                    () -> Component.literal("TPE lie a " + companyId + " donne a " + finalTarget.getName().getString() + "."),
                    true
            );
            target.displayClientMessage(
                    Component.literal("Vous avez recu un TPE lie a l'entreprise " + companyId + "."),
                    false
            );
            return 1;
        }
    }
}
