package org.bb.bb_economy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.bb.bb_economy.Msg;
import org.bb.bb_economy.database.BankLedger;
import org.bb.bb_economy.database.BankManager;
import org.bb.bb_economy.database.Money;
import org.bb.bb_economy.database.ShopCatalog;
import org.bb.bb_economy.database.ShopManager;
import org.bb.bb_economy.database.TxResult;
import org.bb.bb_economy.init.ModBlocks;
import org.bb.bb_economy.init.ModNetworking;
import org.bb.bb_economy.item.ShopItem;
import org.bb.bb_economy.item.ShopItems;
import org.bb.bb_economy.item.TpeItem;
import org.bb.bb_economy.network.OpenPinSetupPacket;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Commandes d'administration {@code /bb_economy ...} (permission 2). Les appels base y sont bloquants : ce sont
 * des commandes rares, lancees a la main par un administrateur, pas des actions de jeu (voir DbExecutor pour
 * celles-ci).
 */
public final class AdminCommands {

    private static final int DEFAULT_HISTORY = 10;
    private static final int MAX_HISTORY = 50;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private static final List<String> HELP = List.of(
            "/bb_economy help - Affiche cette aide",
            "--- Joueurs ---",
            "/bb_economy bank create <joueur> - Cree un compte bancaire et une carte pour le joueur",
            "/bb_economy bank reissue_card <joueur> - Reemet la carte : PIN par defaut, verrouillage efface, NOUVEAU numero "
                    + "(l'ancienne carte, perdue ou dupliquee, cesse de fonctionner)",
            "/bb_economy card regive <joueur> - Redonne la carte actuelle du joueur (meme numero, meme PIN)",
            "/bb_economy bank info <joueur> - Numero de compte, solde, etat et cartes",
            "/bb_economy bank credit <joueur> <montant> - Ajoute de l'argent sur le compte (trace dans le grand livre)",
            "/bb_economy bank debit <joueur> <montant> - Retire de l'argent du compte (trace dans le grand livre)",
            "/bb_economy bank history <joueur> [nombre] - Dernieres transactions (10 par defaut, 50 maximum)",
            "/bb_economy bank freeze <joueur> - Gele le compte : il ne peut plus rien emettre mais peut recevoir",
            "/bb_economy bank unfreeze <joueur> - Degele le compte",
            "/bb_economy bank unlock <joueur> - Leve le verrouillage du PIN (trop d'essais rates) sans changer le PIN",
            "--- Entreprises (nom entre guillemets s'il contient des espaces) ---",
            "/bb_economy company open_account <entreprise> - Ouvre le compte d'une entreprise deja creee (sans proprietaire)",
            "/bb_economy company open_all_accounts - Ouvre le compte de toutes les entreprises qui n'en ont pas "
                    + "(un compte s'ouvre aussi tout seul au premier paiement TPE ou a la remise d'un TPE)",
            "/bb_economy company info <entreprise> - Numero de compte (a donner pour un virement), solde et etat",
            "/bb_economy company credit <entreprise> <montant> - Ajoute de l'argent (ouvre le compte au besoin)",
            "/bb_economy company debit <entreprise> <montant> - Retire de l'argent du compte",
            "/bb_economy company history <entreprise> [nombre] - Dernieres transactions",
            "/bb_economy company freeze <entreprise> - Gele le compte de l'entreprise",
            "/bb_economy company unfreeze <entreprise> - Degele le compte de l'entreprise",
            "/bb_economy give tpe <entreprise> [joueur] - Donne un TPE lie a une entreprise",
            "--- Boutiques automatiques (sans vendeur : offres du metier, compte de l'entreprise liee) ---",
            "/bb_economy give shop <entreprise> [joueur] - Donne un bloc boutique lie a une entreprise",
            "/bb_economy shop list <metier> - Offres du metier (sell = le joueur vend, buy = le joueur achete)",
            "/bb_economy shop set <metier> <sell|buy> <quantite> <prix> [objet] - Offre : <quantite> objets pour <prix> (objet en main si omis)",
            "/bb_economy shop remove <metier> <sell|buy> [objet] - Supprime l'offre (objet en main si omis)",
            "/bb_economy shop enable|disable <metier> <sell|buy> [objet] - Active ou desactive l'offre (objet en main si omis)",
            "--- Objets ---",
            "/give <joueur> bb_economy:money <quantite> - Donne des billets",
            "/give <joueur> bb_economy:wallet - Donne un portefeuille",
            "/give <joueur> bb_economy:atm - Donne un distributeur ATM",
            "/give <joueur> bb_economy:card - Donne une carte vide (inutilisable : preferer card regive)"
    );

    /** Compte vise par une commande, avec un libelle lisible pour les messages. */
    private record Target(String label, String accountNumber) {
    }

    @FunctionalInterface
    private interface TargetResolver {
        /** Retourne la cible, ou null apres avoir signale l'echec a la source. */
        Target resolve(CommandContext<CommandSourceStack> ctx, boolean createIfMissing) throws CommandSyntaxException;
    }

    @FunctionalInterface
    private interface TargetAction {
        int run(CommandSourceStack source, Target target);
    }

    private AdminCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> bank = Commands.literal("bank")
                .then(Commands.literal("create").then(playerArg().executes(ctx ->
                        createPlayerBankAccount(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("reissue_card").then(playerArg().executes(ctx ->
                        reissuePlayerCard(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("unlock").then(playerArg().executes(ctx ->
                        unlockPin(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))));
        addAccountVerbs(bank, AdminCommands::playerArg, AdminCommands::playerTarget);

        LiteralArgumentBuilder<CommandSourceStack> company = Commands.literal("company")
                .then(Commands.literal("open_account").then(companyArg().executes(ctx ->
                        openCompanyAccount(ctx.getSource(), StringArgumentType.getString(ctx, "company")))))
                .then(Commands.literal("open_all_accounts").executes(ctx -> openMissingCompanyAccounts(ctx.getSource())));
        addAccountVerbs(company, AdminCommands::companyArg, AdminCommands::companyTarget);

        dispatcher.register(Commands.literal("bb_economy")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("help").executes(ctx -> showHelp(ctx.getSource())))
                .then(bank)
                .then(company)
                .then(Commands.literal("card")
                        .then(Commands.literal("regive").then(playerArg().executes(ctx ->
                                regivePlayerCard(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"))))))
                .then(shopCommands())
                .then(Commands.literal("give")
                        .then(Commands.literal("tpe")
                                .then(companyArg()
                                        .executes(ctx -> giveCompanyTpe(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "company"), null))
                                        .then(playerArg().executes(ctx -> giveCompanyTpe(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "company"),
                                                EntityArgument.getPlayer(ctx, "player"))))))
                        .then(Commands.literal("shop")
                                .then(companyArg()
                                        .executes(ctx -> giveCompanyShop(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "company"), null))
                                        .then(playerArg().executes(ctx -> giveCompanyShop(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "company"),
                                                EntityArgument.getPlayer(ctx, "player"))))))));
    }

    /** /bb_economy shop ... : gestion des offres des boutiques automatiques (table bb_shop_offers). */
    private static LiteralArgumentBuilder<CommandSourceStack> shopCommands() {
        return Commands.literal("shop")
                .then(Commands.literal("list").then(professionArg().executes(ctx ->
                        shopList(ctx.getSource(), StringArgumentType.getString(ctx, "profession")))))
                .then(Commands.literal("set").then(professionArg().then(directionArg()
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, ShopCatalog.MAX_BUNDLE_SIZE))
                                .then(Commands.argument("price", StringArgumentType.word())
                                        .executes(ctx -> shopSet(ctx, false))
                                        .then(itemArg().executes(ctx -> shopSet(ctx, true))))))))
                .then(shopChangeCommand("remove", ShopChange.REMOVE))
                .then(shopChangeCommand("enable", ShopChange.ENABLE))
                .then(shopChangeCommand("disable", ShopChange.DISABLE));
    }

    /** remove / enable / disable : l'objet est celui en main, ou celui donne en dernier argument. */
    private static LiteralArgumentBuilder<CommandSourceStack> shopChangeCommand(String name, ShopChange change) {
        return Commands.literal(name).then(professionArg().then(directionArg()
                .executes(ctx -> shopChange(ctx, change, false))
                .then(itemArg().executes(ctx -> shopChange(ctx, change, true)))));
    }

    /** Identifiant d'objet, complete par la liste des objets du jeu (y compris ceux des mods). */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> itemArg() {
        return Commands.argument("item", ResourceLocationArgument.id())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> professionArg() {
        return Commands.argument("profession", StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> directionArg() {
        return Commands.argument("direction", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("sell", "buy"), builder));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> playerArg() {
        return Commands.argument("player", EntityArgument.player());
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> companyArg() {
        return Commands.argument("company", StringArgumentType.string());
    }

    /** Verbes communs aux joueurs et aux entreprises : info, credit, debit, history, freeze, unfreeze. */
    private static void addAccountVerbs(LiteralArgumentBuilder<CommandSourceStack> root,
                                        Supplier<RequiredArgumentBuilder<CommandSourceStack, ?>> arg,
                                        TargetResolver resolver) {
        root.then(Commands.literal("info").then(arg.get().executes(ctx ->
                withTarget(ctx, resolver, false, AdminCommands::showInfo))));

        root.then(Commands.literal("credit").then(arg.get()
                .then(Commands.argument("amount", StringArgumentType.word()).executes(ctx ->
                        adjust(ctx, resolver, true)))));
        root.then(Commands.literal("debit").then(arg.get()
                .then(Commands.argument("amount", StringArgumentType.word()).executes(ctx ->
                        adjust(ctx, resolver, false)))));

        root.then(Commands.literal("history").then(arg.get()
                .executes(ctx -> withTarget(ctx, resolver, false,
                        (source, target) -> showHistory(source, target, DEFAULT_HISTORY)))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_HISTORY)).executes(ctx ->
                        withTarget(ctx, resolver, false, (source, target) ->
                                showHistory(source, target, IntegerArgumentType.getInteger(ctx, "count")))))));

        root.then(Commands.literal("freeze").then(arg.get().executes(ctx ->
                withTarget(ctx, resolver, false, (source, target) -> setFrozen(source, target, true)))));
        root.then(Commands.literal("unfreeze").then(arg.get().executes(ctx ->
                withTarget(ctx, resolver, false, (source, target) -> setFrozen(source, target, false)))));
    }

    // -------------------------------------------------------------------------
    // Resolution de la cible
    // -------------------------------------------------------------------------

    private static Target playerTarget(CommandContext<CommandSourceStack> ctx, boolean createIfMissing) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        try {
            return new Target(player.getName().getString(), BankManager.getAccountNumber(player));
        } catch (RuntimeException e) {
            ctx.getSource().sendFailure(Component.literal(player.getName().getString()
                    + " n'a pas de compte bancaire (ou pas de profil actif)."));
            return null;
        }
    }

    private static Target companyTarget(CommandContext<CommandSourceStack> ctx, boolean createIfMissing) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "company");
        Integer companyId = resolveCompany(source, name);
        if (companyId == null) {
            return null;
        }
        Optional<String> account = createIfMissing
                ? Optional.of(BankManager.ensureCompanyAccount(companyId))
                : BankManager.findCompanyAccountNumber(companyId);
        if (account.isEmpty()) {
            source.sendFailure(Component.literal("L'entreprise " + name + " n'a pas encore de compte bancaire "
                    + "(il s'ouvre au premier paiement TPE, ou avec /bb_economy company open_account)."));
            return null;
        }
        return new Target(name, account.get());
    }

    /** Resout une entreprise (table de l'autre mod) d'apres son nom, ou signale l'echec a la source. */
    private static Integer resolveCompany(CommandSourceStack source, String name) {
        try {
            Integer id = BankManager.findCompanyIdByName(name).orElse(null);
            if (id == null) {
                source.sendFailure(Component.literal("Entreprise introuvable : " + name));
            }
            return id;
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Lecture des entreprises impossible : " + e.getMessage()));
            return null;
        }
    }

    private static int withTarget(CommandContext<CommandSourceStack> ctx, TargetResolver resolver, boolean createIfMissing,
                                  TargetAction action) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        try {
            Target target = resolver.resolve(ctx, createIfMissing);
            return target == null ? 0 : action.run(source, target);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Erreur : " + e.getMessage()));
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Verbes communs
    // -------------------------------------------------------------------------

    private static int showInfo(CommandSourceStack source, Target target) {
        BankLedger.AccountInfo info = BankManager.accountInfo(target.accountNumber()).orElse(null);
        if (info == null) {
            source.sendFailure(Component.literal("Compte introuvable : " + target.accountNumber()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Compte de " + target.label() + " : " + info.accountNumber()), false);
        source.sendSuccess(() -> Component.literal(" - Type : " + info.type()), false);
        source.sendSuccess(() -> Component.literal(" - Solde : " + info.balance().toPlainString() + " " + Money.CURRENCY), false);
        source.sendSuccess(() -> Component.literal(" - Etat : " + (info.frozen() ? "GELE" : "actif")), false);
        List<String> cards = BankManager.cardNumbersOf(info.accountNumber());
        if (!cards.isEmpty()) {
            source.sendSuccess(() -> Component.literal(" - Carte(s) : " + String.join(", ", cards)), false);
        }
        return 1;
    }

    private static int adjust(CommandContext<CommandSourceStack> ctx, TargetResolver resolver, boolean credit)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        BigDecimal amount = Money.parsePositive(StringArgumentType.getString(ctx, "amount")).orElse(null);
        if (amount == null) {
            source.sendFailure(Component.literal("Montant invalide (superieur a 0, 2 decimales maximum, jusqu'a "
                    + Money.MAX_AMOUNT.toPlainString() + ")."));
            return 0;
        }
        // Crediter une entreprise sans compte l'ouvre ; la debiter ou la geler n'a pas de sens sans compte.
        return withTarget(ctx, resolver, credit, (src, target) -> {
            TxResult result = credit
                    ? BankManager.adminCredit(target.accountNumber(), amount, src.getServer())
                    : BankManager.adminDebit(target.accountNumber(), amount, src.getServer());
            if (!result.isOk()) {
                src.sendFailure(Component.literal((credit ? "Credit" : "Debit") + " refuse : ").append(Msg.component(result.message())));
                return 0;
            }
            String balance = BankManager.accountInfo(target.accountNumber())
                    .map(info -> info.balance().toPlainString()).orElse("?");
            src.sendSuccess(() -> Component.literal(amount.toPlainString() + " " + Money.CURRENCY + " " + (credit ? "credites sur" : "debites de")
                    + " le compte de " + target.label() + ". Nouveau solde : " + balance + " " + Money.CURRENCY + "."), true);
            return 1;
        });
    }

    private static int showHistory(CommandSourceStack source, Target target, int count) {
        List<BankLedger.TxRecord> records = BankManager.recentTransactions(target.accountNumber(), count);
        if (records.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Aucune transaction pour " + target.label() + "."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Dernieres transactions de " + target.label()
                + " (" + target.accountNumber() + ") :"), false);
        for (BankLedger.TxRecord record : records) {
            String date = record.date() == null ? "?" : record.date().format(DATE_FORMAT);
            String line = " [" + date + "] " + record.type() + " " + record.amount().toPlainString() + " " + Money.CURRENCY + " : "
                    + describeEnd(record.origin(), target.accountNumber(), "especes / creation") + " -> "
                    + describeEnd(record.target(), target.accountNumber(), "especes");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return records.size();
    }

    private static String describeEnd(String account, String thisAccount, String whenNull) {
        if (account == null) {
            return whenNull;
        }
        return account.equals(thisAccount) ? "ce compte" : account;
    }

    private static int setFrozen(CommandSourceStack source, Target target, boolean frozen) {
        if (!BankManager.setAccountFrozen(target.accountNumber(), frozen)) {
            source.sendFailure(Component.literal("Compte introuvable : " + target.accountNumber()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Compte de " + target.label() + (frozen ? " gele." : " degele.")), true);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Joueurs
    // -------------------------------------------------------------------------

    private static int showHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("BB Economy - Commandes administrateur :"), false);
        for (String command : HELP) {
            source.sendSuccess(() -> Component.literal(command.startsWith("---") ? command : " - " + command), false);
        }
        return 1;
    }

    private static int regivePlayerCard(CommandSourceStack source, ServerPlayer target) {
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

    private static int createPlayerBankAccount(CommandSourceStack source, ServerPlayer target) {
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

        openPinSetup(target);

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

    private static int reissuePlayerCard(CommandSourceStack source, ServerPlayer target) {
        final String cardNumber;
        try {
            cardNumber = BankManager.reissueCard(target);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Reemission de carte impossible : " + e.getMessage()));
            return 0;
        }

        openPinSetup(target);

        source.sendSuccess(
                () -> Component.literal("Nouvelle carte " + cardNumber + " remise a " + target.getName().getString()
                        + " (l'ancienne ne fonctionne plus)."),
                true
        );
        target.displayClientMessage(
                Component.literal("Votre carte bancaire a ete remplacee (" + cardNumber + "). Choisissez maintenant un nouveau PIN."),
                false
        );
        return 1;
    }

    private static int unlockPin(CommandSourceStack source, ServerPlayer target) {
        final String accountNumber;
        try {
            accountNumber = BankManager.getAccountNumber(target);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal(target.getName().getString() + " n'a pas de compte bancaire."));
            return 0;
        }
        BankManager.clearPinLocks(accountNumber);
        source.sendSuccess(
                () -> Component.literal("Verrouillage du PIN leve pour " + target.getName().getString() + "."),
                true
        );
        return 1;
    }

    private static void openPinSetup(ServerPlayer target) {
        ModNetworking.CHANNEL.sendTo(
                new OpenPinSetupPacket(),
                target.connection.connection,
                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
        );
    }

    // -------------------------------------------------------------------------
    // Entreprises
    // -------------------------------------------------------------------------

    private static int openCompanyAccount(CommandSourceStack source, String companyName) {
        Integer companyId = resolveCompany(source, companyName);
        if (companyId == null) {
            return 0;
        }

        final String accountNumber;
        try {
            accountNumber = BankManager.openCompanyAccount(companyId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Ouverture du compte impossible : " + e.getMessage()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Compte " + accountNumber + " ouvert pour l'entreprise " + companyName + "."),
                true
        );
        return 1;
    }

    private static int openMissingCompanyAccounts(CommandSourceStack source) {
        final int opened;
        try {
            opened = BankManager.openMissingCompanyAccounts();
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Ouverture des comptes impossible : " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(opened + " compte(s) d'entreprise ouvert(s)."),
                true
        );
        return opened;
    }

    // -------------------------------------------------------------------------
    // Boutiques automatiques
    // -------------------------------------------------------------------------

    private enum ShopChange { REMOVE, ENABLE, DISABLE }

    /** Sens d'une offre, du point de vue du joueur : sell = il vend a la boutique, buy = il lui achete. */
    private static ShopCatalog.Direction parseDirection(CommandSourceStack source, String raw) {
        Optional<ShopCatalog.Direction> direction = ShopCatalog.Direction.fromColumn(raw);
        if (direction.isEmpty()) {
            source.sendFailure(Component.literal("Sens invalide : " + raw
                    + " (sell = le joueur vend a la boutique, buy = le joueur lui achete)."));
        }
        return direction.orElse(null);
    }

    /** Verifie que le metier existe (table du plugin des entreprises) ; retourne son code normalise ou null. */
    private static String requireProfession(CommandSourceStack source, String raw) {
        String code = raw.trim().toUpperCase();
        try {
            if (!ShopManager.catalog().professionExists(code)) {
                source.sendFailure(Component.literal("Metier introuvable : " + code
                        + " (le code est celui de la table bb_professions, ex. SCIERIE)."));
                return null;
            }
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Lecture des metiers impossible : " + e.getMessage()));
            return null;
        }
        return code;
    }

    private static int shopList(CommandSourceStack source, String rawProfession) {
        String profession = requireProfession(source, rawProfession);
        if (profession == null) {
            return 0;
        }
        List<ShopCatalog.Offer> offers;
        try {
            offers = ShopManager.catalog().offersOfProfession(profession);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Lecture des offres impossible : " + e.getMessage()));
            return 0;
        }
        if (offers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Aucune offre pour " + profession + "."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("Offres de " + profession + " (sell = le joueur vend, buy = le joueur achete) :"), false);
        for (ShopCatalog.Offer offer : offers) {
            String line = " " + offer.direction().column() + " " + offer.itemKey() + " : " + offer.bundleSize()
                    + " pour " + offer.bundlePrice().toPlainString() + " " + Money.CURRENCY + (offer.active() ? "" : " (DESACTIVEE)");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return offers.size();
    }

    private static int shopSet(CommandContext<CommandSourceStack> ctx, boolean itemGiven) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ShopCatalog.Direction direction = parseDirection(source, StringArgumentType.getString(ctx, "direction"));
        BigDecimal price = Money.parsePositive(StringArgumentType.getString(ctx, "price")).orElse(null);
        int quantity = IntegerArgumentType.getInteger(ctx, "quantity");
        if (direction == null) {
            return 0;
        }
        if (price == null) {
            source.sendFailure(Component.literal("Prix invalide (superieur a 0, 2 decimales maximum)."));
            return 0;
        }
        String profession = requireProfession(source, StringArgumentType.getString(ctx, "profession"));
        String itemKey = offerItemKey(ctx, itemGiven);
        if (profession == null || itemKey == null) {
            return 0;
        }
        try {
            boolean updated = ShopManager.catalog().upsertOffer(profession, itemKey, direction, quantity, price);
            source.sendSuccess(() -> Component.literal((updated ? "Offre mise a jour : " : "Offre creee : ") + profession + " "
                    + direction.column() + " " + quantity + " x " + itemKey + " pour " + price.toPlainString() + " " + Money.CURRENCY + "."), true);
            return 1;
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Ecriture de l'offre impossible : " + e.getMessage()));
            return 0;
        }
    }

    private static int shopChange(CommandContext<CommandSourceStack> ctx, ShopChange change, boolean itemGiven)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ShopCatalog.Direction direction = parseDirection(source, StringArgumentType.getString(ctx, "direction"));
        if (direction == null) {
            return 0;
        }
        String profession = requireProfession(source, StringArgumentType.getString(ctx, "profession"));
        String itemKey = offerItemKey(ctx, itemGiven);
        if (profession == null || itemKey == null) {
            return 0;
        }
        try {
            ShopCatalog catalog = ShopManager.catalog();
            boolean found = switch (change) {
                case REMOVE -> catalog.removeOffer(profession, itemKey, direction);
                case ENABLE -> catalog.setActive(profession, itemKey, direction, true);
                case DISABLE -> catalog.setActive(profession, itemKey, direction, false);
            };
            if (!found) {
                source.sendFailure(Component.literal("Aucune offre " + direction.column() + " pour " + itemKey + " chez " + profession + "."));
                return 0;
            }
            String done = switch (change) {
                case REMOVE -> "supprimee";
                case ENABLE -> "activee";
                case DISABLE -> "desactivee";
            };
            source.sendSuccess(() -> Component.literal("Offre " + done + " : " + profession + " " + direction.column() + " " + itemKey + "."), true);
            return 1;
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Modification de l'offre impossible : " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Objet d'une offre : celui donne en argument (verifie contre le registre du jeu), sinon celui tenu en main.
     * Retourne null apres avoir signale l'echec.
     */
    private static String offerItemKey(CommandContext<CommandSourceStack> ctx, boolean itemGiven) throws CommandSyntaxException {
        if (!itemGiven) {
            return heldItemKey(ctx.getSource());
        }
        String key = ResourceLocationArgument.getId(ctx, "item").toString();
        if (ShopItems.itemOf(key).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Objet inconnu : " + key));
            return null;
        }
        return key;
    }

    /** Identifiant de l'objet tenu en main par l'administrateur, ou null apres avoir signale l'echec. */
    private static String heldItemKey(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer admin = source.getPlayerOrException();
        Optional<String> key = ShopItems.keyOf(admin.getMainHandItem());
        if (key.isEmpty()) {
            source.sendFailure(Component.literal("Tenez en main l'objet de l'offre."));
            return null;
        }
        return key.get();
    }

    private static int giveCompanyShop(CommandSourceStack source, String companyName, ServerPlayer explicitTarget) {
        Integer companyId = resolveCompany(source, companyName);
        if (companyId == null) {
            return 0;
        }
        try {
            BankManager.ensureCompanyAccount(companyId);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Ouverture du compte de l'entreprise impossible : " + e.getMessage()));
            return 0;
        }

        ServerPlayer target = explicitTarget;
        if (target == null) {
            try {
                target = source.getPlayerOrException();
            } catch (Exception e) {
                source.sendFailure(Component.literal("Precisez un joueur cible depuis la console."));
                return 0;
            }
        }

        ItemStack stack = new ItemStack(ModBlocks.SHOP_BLOCK_ITEM.get());
        ShopItem.setCompanyId(stack, String.valueOf(companyId));
        if (!target.getInventory().add(stack)) {
            target.drop(stack, false);
        }

        ServerPlayer finalTarget = target;
        source.sendSuccess(
                () -> Component.literal("Boutique automatique de " + companyName + " donnee a " + finalTarget.getName().getString()
                        + ". Elle propose les offres du metier de l'entreprise."),
                true
        );
        return 1;
    }

    private static int giveCompanyTpe(CommandSourceStack source, String companyName, ServerPlayer explicitTarget) {
        Integer companyId = resolveCompany(source, companyName);
        if (companyId == null) {
            return 0;
        }

        final String companyAccount;
        try {
            companyAccount = BankManager.ensureCompanyAccount(companyId);
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("Ouverture du compte de l'entreprise impossible : " + e.getMessage()));
            return 0;
        }

        ServerPlayer target = explicitTarget;
        if (target == null) {
            try {
                target = source.getPlayerOrException();
            } catch (Exception e) {
                source.sendFailure(Component.literal("Precisez un joueur cible depuis la console."));
                return 0;
            }
        }

        // Le TPE memorise l'id numerique de l'entreprise (le nom peut changer) et son compte, pour affichage.
        ItemStack stack = new ItemStack(ModBlocks.TPE_BLOCK_ITEM.get());
        TpeItem.setCompanyData(stack, String.valueOf(companyId), companyAccount);

        boolean added = target.getInventory().add(stack);
        if (!added) {
            target.drop(stack, false);
        }

        ServerPlayer finalTarget = target;
        source.sendSuccess(
                () -> Component.literal("TPE lie a " + companyName + " donne a " + finalTarget.getName().getString() + "."),
                true
        );
        target.displayClientMessage(
                Component.literal("Vous avez recu un TPE lie a l'entreprise " + companyName + "."),
                false
        );
        return 1;
    }
}
