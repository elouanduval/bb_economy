package org.bb.bb_economy.database;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bb.bb_economy.Config;
import org.bb.bb_economy.init.ModItems;
import org.bb.bb_economy.item.CardItem;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Facade cote Minecraft : traduit joueurs, inventaires et items en operations sur {@link BankLedger}.
 * Toute la logique monetaire (verrous, plafonds, atomicite) vit dans le ledger ; ici on gere
 * l'identification du joueur, les billets et les cartes/PIN.
 *
 * Ces methodes touchent a l'inventaire et doivent donc etre appelees depuis le thread serveur
 * (sauf {@link #processSalaries}, qui ne manipule que la base).
 */
public final class BankManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BankLedger LEDGER = new BankLedger(DatabaseManager::getConnection);
    private static final PinAttemptTracker PIN_ATTEMPTS = new PinAttemptTracker();

    private BankManager() {
    }

    /** Resultat d'une verification de PIN. */
    public record PinCheck(Status status, int attemptsLeft, long lockSeconds) {

        public enum Status { OK, WRONG, LOCKED, NO_CARD }

        public boolean isOk() {
            return status == Status.OK;
        }

        public String message() {
            return switch (status) {
                case OK -> "PIN valide.";
                case WRONG -> attemptsLeft > 0
                        ? "PIN incorrect. Essais restants : " + attemptsLeft + "."
                        : "PIN incorrect. Compte verrouille pendant " + formatDuration(lockSeconds) + ".";
                case LOCKED -> "Trop d'essais. Reessayez dans " + formatDuration(lockSeconds) + ".";
                case NO_CARD -> "Aucune carte bancaire disponible.";
            };
        }

        private static String formatDuration(long seconds) {
            return seconds >= 60 ? (seconds + 59) / 60 + " min" : seconds + " s";
        }
    }

    private record CardRow(String cardNumber, String pinHash) {
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    // -------------------------------------------------------------------------
    // Acces base
    // -------------------------------------------------------------------------

    private static <T> T query(String sql, Binder binder, Mapper<T> mapper) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                return mapper.map(rs);
            }
        } catch (SQLException e) {
            throw new BankException("Erreur base de donnees : " + e.getMessage(), e);
        }
    }

    private static int update(String sql, Binder binder) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new BankException("Erreur base de donnees : " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Profil actif (table geree par l'autre mod)
    // -------------------------------------------------------------------------

    /** Retourne l'id du profil actif du joueur, ou -1 s'il n'en a pas. */
    public static int getActiveProfileId(Player player) {
        return query("SELECT active_profile FROM mc_accounts WHERE uuid = ?",
                stmt -> stmt.setString(1, player.getUUID().toString()),
                rs -> rs.next() && rs.getObject(1) != null ? rs.getInt(1) : -1);
    }

    public static boolean hasActiveProfile(Player player) {
        return getActiveProfileId(player) != -1;
    }

    private static int requireProfileId(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) {
            throw new BankException("Aucun profil actif pour " + player.getName().getString());
        }
        return profileId;
    }

    // -------------------------------------------------------------------------
    // Compte bancaire
    // -------------------------------------------------------------------------

    public static boolean hasAccount(Player player) {
        int profileId = getActiveProfileId(player);
        return profileId != -1 && LEDGER.findPersonalAccountNumber(profileId).isPresent();
    }

    public static String getAccountNumber(Player player) {
        int profileId = requireProfileId(player);
        return LEDGER.findPersonalAccountNumber(profileId)
                .orElseThrow(() -> new BankException("Compte introuvable pour le profil " + profileId));
    }

    /** Solde du compte personnel, ou vide si le joueur n'a ni profil actif ni compte. */
    public static Optional<BigDecimal> findBalance(Player player) {
        int profileId = getActiveProfileId(player);
        return profileId == -1 ? Optional.empty() : LEDGER.findPersonalBalance(profileId);
    }

    /** Solde pour l'affichage : 0 si le compte est introuvable ou la base indisponible. */
    public static BigDecimal balanceOrZero(Player player) {
        try {
            return findBalance(player).orElse(BigDecimal.ZERO);
        } catch (BankException e) {
            LOGGER.error("Lecture du solde impossible pour {}", player.getName().getString(), e);
            return BigDecimal.ZERO;
        }
    }

    // -------------------------------------------------------------------------
    // Entreprises
    // -------------------------------------------------------------------------

    public static boolean companyExists(String companyId) {
        return query("SELECT 1 FROM bb_companies WHERE company_id = ?",
                stmt -> stmt.setString(1, normalizeCompanyId(companyId)),
                ResultSet::next);
    }

    public static String getCompanyAccountNumber(String companyId) {
        return query("SELECT account_number FROM bb_companies WHERE company_id = ?",
                stmt -> stmt.setString(1, normalizeCompanyId(companyId)),
                rs -> {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                    throw new BankException("Entreprise introuvable : " + companyId);
                });
    }

    /** Sera remplace par une verification LuckPerms ; garde ici pour l'instant (table bb_jobs). */
    public static boolean playerWorksForCompany(Player player, String companyId) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        return query("SELECT 1 FROM bb_jobs WHERE owner = ? AND company_id = ? LIMIT 1",
                stmt -> {
                    stmt.setInt(1, profileId);
                    stmt.setString(2, normalizeCompanyId(companyId));
                },
                ResultSet::next);
    }

    // -------------------------------------------------------------------------
    // Cartes bancaires
    // -------------------------------------------------------------------------

    private static List<CardRow> loadCards(int profileId) {
        return query("SELECT bc.card_number, bc.card_pin FROM bb_bank_cards bc " +
                        "JOIN bb_bank_accounts ba ON bc.account_number = ba.account_number " +
                        "WHERE ba.owner = ? AND ba.account_type = ?",
                stmt -> {
                    stmt.setInt(1, profileId);
                    stmt.setString(2, BankLedger.PERSONAL);
                },
                rs -> {
                    List<CardRow> cards = new ArrayList<>();
                    while (rs.next()) {
                        cards.add(new CardRow(rs.getString(1), rs.getString(2)));
                    }
                    return cards;
                });
    }

    public static boolean hasDefaultCardPin(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        String defaultPin = defaultPin();
        return loadCards(profileId).stream().anyMatch(card -> PinHasher.verify(defaultPin, card.pinHash()));
    }

    public static String getCardNumber(Player player) {
        int profileId = requireProfileId(player);
        List<CardRow> cards = loadCards(profileId);
        if (cards.isEmpty()) {
            throw new BankException("Carte introuvable pour le profil " + profileId);
        }
        return cards.get(0).cardNumber();
    }

    public static boolean playerOwnsCard(Player player, String cardNumber) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1 || cardNumber == null || cardNumber.isBlank()) return false;
        return loadCards(profileId).stream().anyMatch(card -> card.cardNumber().equals(cardNumber));
    }

    /**
     * Vrai si le joueur tient (main ou seconde main) une carte qui lui appartient.
     * En cas d'erreur de base de donnees on refuse plutot que de laisser passer.
     */
    public static boolean hasOwnedCardInHand(Player player) {
        try {
            return isOwnedCard(player, player.getMainHandItem()) || isOwnedCard(player, player.getOffhandItem());
        } catch (BankException e) {
            LOGGER.error("Verification de la carte impossible pour {}", player.getName().getString(), e);
            return false;
        }
    }

    private static boolean isOwnedCard(Player player, ItemStack stack) {
        if (stack.getItem() != ModItems.CARD.get()) return false;
        String cardNumber = CardItem.getCardNumber(stack);
        return !cardNumber.isBlank() && playerOwnsCard(player, cardNumber);
    }

    public static PinCheck checkPin(Player player, int pin) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return new PinCheck(PinCheck.Status.NO_CARD, 0, 0);
        Optional<String> account = LEDGER.findPersonalAccountNumber(profileId);
        if (account.isEmpty()) return new PinCheck(PinCheck.Status.NO_CARD, 0, 0);
        String accountNumber = account.get();

        long locked = PIN_ATTEMPTS.remainingLockSeconds(accountNumber);
        if (locked > 0) {
            return new PinCheck(PinCheck.Status.LOCKED, 0, locked);
        }
        if (pin < 0 || pin > 9999) {
            return recordFailedPin(accountNumber);
        }

        String candidate = String.format("%04d", pin);
        List<CardRow> cards = loadCards(profileId);
        if (cards.isEmpty()) return new PinCheck(PinCheck.Status.NO_CARD, 0, 0);

        for (CardRow card : cards) {
            if (PinHasher.verify(candidate, card.pinHash())) {
                PIN_ATTEMPTS.reset(accountNumber);
                if (PinHasher.needsUpgrade(card.pinHash())) {
                    upgradePinHash(card.cardNumber(), candidate);
                }
                return new PinCheck(PinCheck.Status.OK, 0, 0);
            }
        }
        return recordFailedPin(accountNumber);
    }

    private static PinCheck recordFailedPin(String accountNumber) {
        int left = PIN_ATTEMPTS.recordFailure(accountNumber, Config.PIN_MAX_ATTEMPTS, Config.PIN_LOCKOUT_SECONDS * 1000L);
        return new PinCheck(PinCheck.Status.WRONG, left, left == 0 ? Config.PIN_LOCKOUT_SECONDS : 0);
    }

    private static void upgradePinHash(String cardNumber, String pin) {
        try {
            update("UPDATE bb_bank_cards SET card_pin = ? WHERE card_number = ?", stmt -> {
                stmt.setString(1, PinHasher.hash(pin));
                stmt.setString(2, cardNumber);
            });
        } catch (BankException e) {
            LOGGER.warn("Migration du hash de PIN impossible pour la carte {}", cardNumber, e);
        }
    }

    public static boolean updateCardPin(Player player, String pin) {
        if (!isValidPin(pin)) return false;
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        Optional<String> account = LEDGER.findPersonalAccountNumber(profileId);
        if (account.isEmpty()) return false;

        String newHash = PinHasher.hash(pin);
        int rows = update("UPDATE bb_bank_cards SET card_pin = ? WHERE account_number = ?", stmt -> {
            stmt.setString(1, newHash);
            stmt.setString(2, account.get());
        });
        if (rows > 0) {
            PIN_ATTEMPTS.reset(account.get());
        }
        return rows > 0;
    }

    public static boolean resetCardPinToDefault(Player player) {
        return updateCardPin(player, defaultPin());
    }

    /** Cree le compte personnel et la carte, puis remet les objets prevus par la configuration. */
    public static String createBankAccountAndCard(Player player) {
        int profileId = requireProfileId(player);

        BigDecimal startingBalance = BigDecimal.valueOf(Config.STARTING_BANK_BALANCE)
                .setScale(2, RoundingMode.HALF_UP)
                .min(Money.MAX_AMOUNT);
        Optional<BankLedger.Created> created = LEDGER.createPersonalAccount(
                profileId, PinHasher.hash(defaultPin()), startingBalance, currentInGameDay(player));
        if (created.isEmpty()) {
            throw new IllegalStateException("Un compte bancaire existe deja pour le profil " + profileId);
        }

        String cardNumber = created.get().cardNumber();
        if (Config.GIVE_STARTER_CARD) {
            giveCardItemByNumber(player, cardNumber);
        }
        if (Config.GIVE_STARTER_WALLET) {
            giveWalletItem(player);
        }
        if (Config.STARTING_CASH_INVENTORY > 0) {
            addMoneyStacks(player, Config.STARTING_CASH_INVENTORY);
            syncInventory(player);
        }
        return cardNumber;
    }

    public static String reissueCard(Player player) {
        String cardNumber = getCardNumber(player);
        if (!resetCardPinToDefault(player)) {
            throw new BankException("Impossible de reinitialiser le PIN de la carte.");
        }
        if (!giveCardItemByNumber(player, cardNumber)) {
            throw new BankException("Impossible de redonner la carte bancaire.");
        }
        return cardNumber;
    }

    // -------------------------------------------------------------------------
    // Operations d'argent
    // -------------------------------------------------------------------------

    /**
     * Depot de billets. Les billets sont retires AVANT le credit : si le serveur s'arrete entre les deux,
     * le joueur perd des billets au lieu d'en dupliquer. Ils sont rendus si le depot est refuse.
     */
    public static TxResult deposit(Player player, BigDecimal amount) {
        if (!Money.isValid(amount) || !Money.isWhole(amount)) return TxResult.INVALID_AMOUNT;
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return TxResult.NO_ACCOUNT;

        int bills = Money.toBills(amount);
        if (countBills(player) < bills) return TxResult.NOT_ENOUGH_CASH;

        removeBills(player, bills);
        final TxResult result;
        try {
            result = LEDGER.deposit(profileId, amount, dailyLimit(Config.DAILY_DEPOSIT_LIMIT), currentInGameDay(player));
        } catch (RuntimeException e) {
            addMoneyStacks(player, bills);
            syncInventory(player);
            throw e;
        }
        if (!result.isOk()) {
            addMoneyStacks(player, bills);
        }
        syncInventory(player);
        return result;
    }

    /** Retrait de billets : le compte est debite puis les billets sont remis (ou jetes au sol si l'inventaire est plein). */
    public static TxResult withdraw(Player player, BigDecimal amount) {
        if (!Money.isValid(amount) || !Money.isWhole(amount)) return TxResult.INVALID_AMOUNT;
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return TxResult.NO_ACCOUNT;

        TxResult result = LEDGER.withdraw(profileId, amount, dailyLimit(Config.DAILY_WITHDRAW_LIMIT), currentInGameDay(player));
        if (result.isOk()) {
            addMoneyStacks(player, Money.toBills(amount));
            syncInventory(player);
        }
        return result;
    }

    public static TxResult transfer(Player fromPlayer, String toAccountNumber, BigDecimal amount) {
        int profileId = getActiveProfileId(fromPlayer);
        if (profileId == -1) return TxResult.NO_ACCOUNT;
        return LEDGER.transfer(profileId, toAccountNumber, amount, dailyLimit(Config.DAILY_TRANSFER_LIMIT),
                currentInGameDay(fromPlayer));
    }

    public static TxResult processTpePayment(Player buyer, String companyAccount, BigDecimal amount) {
        int profileId = getActiveProfileId(buyer);
        if (profileId == -1) return TxResult.NO_ACCOUNT;
        return LEDGER.payTpe(profileId, companyAccount, amount, currentInGameDay(buyer));
    }

    /** Verse les salaires dus a ce tick. Appele hors thread serveur : ne touche qu'a la base. */
    public static void processSalaries(long currentDayTime) {
        int tick = (int) (currentDayTime % 24000);
        long inGameDay = currentDayTime / 24000L;

        record Salary(String jobId, BigDecimal amount, int profileId, String companyAccount) {
        }

        final List<Salary> due;
        try {
            due = query("SELECT j.job_id, j.job_salary, j.owner, c.account_number " +
                            "FROM bb_jobs j JOIN bb_companies c ON j.company_id = c.company_id " +
                            "WHERE j.job_salary_time = ?",
                    stmt -> stmt.setInt(1, tick),
                    rs -> {
                        List<Salary> rows = new ArrayList<>();
                        while (rs.next()) {
                            rows.add(new Salary(rs.getString(1), rs.getBigDecimal(2), rs.getInt(3), rs.getString(4)));
                        }
                        return rows;
                    });
        } catch (BankException e) {
            LOGGER.error("Lecture des salaires impossible", e);
            return;
        }

        for (Salary salary : due) {
            try {
                Optional<String> playerAccount = LEDGER.findPersonalAccountNumber(salary.profileId());
                if (playerAccount.isEmpty() || !Money.isValid(salary.amount())) continue;

                TxResult result = LEDGER.transferBetweenAccounts(salary.companyAccount(), playerAccount.get(),
                        salary.amount(), BankLedger.TYPE_SALARY, inGameDay);
                if (result.isOk()) {
                    LOGGER.info("Salaire verse : {} de {} vers {}", salary.amount(), salary.companyAccount(), playerAccount.get());
                } else {
                    LOGGER.warn("Salaire non verse pour le job {} : {}", salary.jobId(), result.message());
                }
            } catch (BankException e) {
                LOGGER.error("Erreur de versement du salaire pour le job {}", salary.jobId(), e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    public static ItemStack createCardItem(String cardNumber) {
        ItemStack stack = new ItemStack(ModItems.CARD.get());
        stack.getOrCreateTag().putString(CardItem.TAG_CARD_NUMBER, cardNumber);
        return stack;
    }

    public static boolean giveCardItemByNumber(Player player, String cardNumber) {
        if (!playerOwnsCard(player, cardNumber)) return false;
        ItemStack cardStack = createCardItem(cardNumber);
        if (!player.getInventory().add(cardStack)) {
            player.drop(cardStack, false);
        }
        markCardItemGiven(cardNumber, player.getUUID().toString());
        return true;
    }

    public static boolean giveWalletItem(Player player) {
        ItemStack walletStack = new ItemStack(ModItems.WALLET.get());
        if (!player.getInventory().add(walletStack)) {
            player.drop(walletStack, false);
        }
        return true;
    }

    /** Trace informative : un echec ne doit pas annuler une carte deja remise au joueur. */
    private static void markCardItemGiven(String cardNumber, String receiverUuid) {
        try {
            update("UPDATE bb_bank_cards SET card_last_item_given_at = CURRENT_TIMESTAMP, card_last_item_receiver = ? " +
                    "WHERE card_number = ?", stmt -> {
                stmt.setString(1, receiverUuid);
                stmt.setString(2, cardNumber);
            });
        } catch (BankException e) {
            LOGGER.warn("Trace de remise de la carte {} impossible", cardNumber, e);
        }
    }

    private static int countBills(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == ModItems.MONEY.get()) total += stack.getCount();
        }
        return total;
    }

    private static void removeBills(Player player, int amount) {
        int remaining = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) break;
            if (stack.getItem() == ModItems.MONEY.get()) {
                int taken = Math.min(stack.getCount(), remaining);
                stack.shrink(taken);
                remaining -= taken;
            }
        }
    }

    private static void addMoneyStacks(Player player, int amount) {
        int remaining = amount;
        int maxStackSize = ModItems.MONEY.get().getDefaultInstance().getMaxStackSize();
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStackSize);
            ItemStack moneyStack = new ItemStack(ModItems.MONEY.get(), stackSize);
            if (!player.getInventory().add(moneyStack)) {
                player.drop(moneyStack, false);
            }
            remaining -= stackSize;
        }
    }

    private static void syncInventory(Player player) {
        player.getInventory().setChanged();
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.containerMenu.broadcastChanges();
            serverPlayer.inventoryMenu.broadcastChanges();
        }
    }

    // -------------------------------------------------------------------------
    // PIN et utilitaires
    // -------------------------------------------------------------------------

    public static boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{4}");
    }

    /** Le PIN par defaut est reserve : le joueur doit en choisir un autre. */
    public static boolean isDefaultPin(String pin) {
        return defaultPin().equals(pin);
    }

    private static String defaultPin() {
        return String.format("%04d", Config.DEFAULT_CARD_PIN);
    }

    public static String normalizeCompanyId(String companyId) {
        if (companyId == null) throw new IllegalArgumentException("companyId ne peut pas etre null");
        String value = companyId.trim().toUpperCase();
        if (value.isBlank()) throw new IllegalArgumentException("companyId ne peut pas etre vide");
        return value;
    }

    private static BigDecimal dailyLimit(double configuredValue) {
        return BigDecimal.valueOf(configuredValue).setScale(2, RoundingMode.HALF_UP);
    }

    /** Jour in-game de l'overworld : identique quelle que soit la dimension ou se trouve le joueur. */
    private static long currentInGameDay(Player player) {
        MinecraftServer server = player.getServer();
        long dayTime = server != null ? server.overworld().getDayTime() : player.level().getDayTime();
        return dayTime / 24000L;
    }
}
