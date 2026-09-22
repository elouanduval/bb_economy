package org.bb.bb_economy;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = BB_Economy.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.ConfigValue<String> DB_HOST_VALUE =
            BUILDER.comment("Adresse du serveur MariaDB").define("database.host", "localhost");

    private static final ForgeConfigSpec.IntValue DB_PORT_VALUE =
            BUILDER.comment("Port MariaDB").defineInRange("database.port", 3306, 1, 65535);

    private static final ForgeConfigSpec.ConfigValue<String> DB_NAME_VALUE =
            BUILDER.comment("Nom de la base de donnees").define("database.name", "bb_economy");

    private static final ForgeConfigSpec.ConfigValue<String> DB_USER_VALUE =
            BUILDER.comment("Utilisateur MariaDB").define("database.user", "root");

    private static final ForgeConfigSpec.ConfigValue<String> DB_PASSWORD_VALUE =
            BUILDER.comment("Mot de passe MariaDB").define("database.password", "password");

    private static final ForgeConfigSpec.DoubleValue STARTING_BANK_BALANCE_VALUE =
            BUILDER.comment("Argent sur le compte bancaire a la creation du compte joueur")
                    .defineInRange("economy.startingBankBalance", 100.0D, 0.0D, 1_000_000_000.0D);

    private static final ForgeConfigSpec.IntValue STARTING_CASH_INVENTORY_VALUE =
            BUILDER.comment("Nombre de billets donnes au joueur a la creation de son compte bancaire")
                    .defineInRange("economy.startingCashInInventory", 0, 0, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.BooleanValue GIVE_STARTER_WALLET_VALUE =
            BUILDER.comment("Donner un portefeuille au joueur a la creation de son compte bancaire")
                    .define("economy.giveStarterWallet", true);

    private static final ForgeConfigSpec.BooleanValue GIVE_STARTER_CARD_VALUE =
            BUILDER.comment("Donner la carte bancaire au joueur a la creation de son compte (sinon : /bb_economy card regive)")
                    .define("economy.giveStarterCard", true);

    private static final ForgeConfigSpec.IntValue DEFAULT_CARD_PIN_VALUE =
            BUILDER.comment("Code PIN par defaut des nouvelles cartes bancaires")
                    .defineInRange("economy.defaultCardPin", 0, 0, 9999);

    private static final ForgeConfigSpec.DoubleValue DAILY_DEPOSIT_LIMIT_VALUE =
            BUILDER.comment("Montant maximum depose par joueur sur une journee in-game")
                    .defineInRange("economy.dailyDepositLimit", 10000.0D, 0.0D, 1_000_000_000.0D);

    private static final ForgeConfigSpec.DoubleValue DAILY_WITHDRAW_LIMIT_VALUE =
            BUILDER.comment("Montant maximum retire par joueur sur une journee in-game")
                    .defineInRange("economy.dailyWithdrawLimit", 10000.0D, 0.0D, 1_000_000_000.0D);

    private static final ForgeConfigSpec.DoubleValue DAILY_TRANSFER_LIMIT_VALUE =
            BUILDER.comment("Montant maximum vire depuis un compte personnel sur une journee in-game")
                    .defineInRange("economy.dailyTransferLimit", 10000.0D, 0.0D, 1_000_000_000.0D);

    private static final ForgeConfigSpec.IntValue WALLET_MAX_STORED_MONEY_VALUE =
            BUILDER.comment("Capacite maximale d'un portefeuille en billets")
                    .defineInRange("economy.walletMaxStoredMoney", 10000, 1, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.IntValue SALARY_RECURRENCE_DAYS_VALUE =
            BUILDER.comment("Nombre de jours in-game entre deux versements de salaire")
                    .defineInRange("economy.salary.recurrenceDays", 1, 1, Integer.MAX_VALUE);

    private static final ForgeConfigSpec.IntValue SALARY_RECURRENCE_TICK_VALUE =
            BUILDER.comment("Tick in-game de versement des salaires (0 = 00:00)")
                    .defineInRange("economy.salary.recurrenceTick", 0, 0, 23999);

    private static final ForgeConfigSpec.IntValue PIN_MAX_ATTEMPTS_VALUE =
            BUILDER.comment("Nombre de PIN errones avant verrouillage temporaire du compte")
                    .defineInRange("economy.pin.maxAttempts", 3, 1, 100);

    private static final ForgeConfigSpec.IntValue PIN_LOCKOUT_SECONDS_VALUE =
            BUILDER.comment("Duree du verrouillage (en secondes reelles) apres trop de PIN errones")
                    .defineInRange("economy.pin.lockoutSeconds", 300, 1, 86_400);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static String DB_HOST;
    public static int DB_PORT;
    public static String DB_NAME;
    public static String DB_USER;
    public static String DB_PASSWORD;
    public static double STARTING_BANK_BALANCE;
    public static int STARTING_CASH_INVENTORY;
    public static boolean GIVE_STARTER_WALLET;
    public static boolean GIVE_STARTER_CARD;
    public static int DEFAULT_CARD_PIN;
    public static double DAILY_DEPOSIT_LIMIT;
    public static double DAILY_WITHDRAW_LIMIT;
    public static double DAILY_TRANSFER_LIMIT;
    public static int WALLET_MAX_STORED_MONEY;
    public static int SALARY_RECURRENCE_DAYS;
    public static int SALARY_RECURRENCE_TICK;
    public static int PIN_MAX_ATTEMPTS = 3;
    public static int PIN_LOCKOUT_SECONDS = 300;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        DB_HOST = DB_HOST_VALUE.get();
        DB_PORT = DB_PORT_VALUE.get();
        DB_NAME = DB_NAME_VALUE.get();
        DB_USER = DB_USER_VALUE.get();
        DB_PASSWORD = DB_PASSWORD_VALUE.get();
        STARTING_BANK_BALANCE = STARTING_BANK_BALANCE_VALUE.get();
        STARTING_CASH_INVENTORY = STARTING_CASH_INVENTORY_VALUE.get();
        GIVE_STARTER_WALLET = GIVE_STARTER_WALLET_VALUE.get();
        GIVE_STARTER_CARD = GIVE_STARTER_CARD_VALUE.get();
        DEFAULT_CARD_PIN = DEFAULT_CARD_PIN_VALUE.get();
        DAILY_DEPOSIT_LIMIT = DAILY_DEPOSIT_LIMIT_VALUE.get();
        DAILY_WITHDRAW_LIMIT = DAILY_WITHDRAW_LIMIT_VALUE.get();
        DAILY_TRANSFER_LIMIT = DAILY_TRANSFER_LIMIT_VALUE.get();
        WALLET_MAX_STORED_MONEY = WALLET_MAX_STORED_MONEY_VALUE.get();
        SALARY_RECURRENCE_DAYS = SALARY_RECURRENCE_DAYS_VALUE.get();
        SALARY_RECURRENCE_TICK = SALARY_RECURRENCE_TICK_VALUE.get();
        PIN_MAX_ATTEMPTS = PIN_MAX_ATTEMPTS_VALUE.get();
        PIN_LOCKOUT_SECONDS = PIN_LOCKOUT_SECONDS_VALUE.get();
    }
}
