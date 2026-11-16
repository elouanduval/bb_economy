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
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Facade cote Minecraft : traduit joueurs, inventaires et items en operations sur {@link BankLedger}.
 * Toute la logique monetaire (verrous, plafonds, atomicite) vit dans le ledger ; ici on gere
 * l'identification du joueur, les billets et les cartes/PIN.
 *
 * Ces methodes touchent a l'inventaire et doivent donc etre appelees depuis le thread serveur.
 */
public final class BankManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BankLedger LEDGER = new BankLedger(DatabaseManager::getConnection);
    private static final PinAttemptTracker PIN_ATTEMPTS = new PinAttemptTracker();

    private BankManager() {
    }

    /** Resultat d'une verification de PIN. */
    public record PinCheck(Status status, int attemptsLeft, long lockSeconds) {

        public enum Status { OK, WRONG, LOCKED, NO_CARD, ERROR }

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
                case ERROR -> TxResult.SERVICE_UNAVAILABLE.message();
            };
        }

        public static PinCheck error() {
            return new PinCheck(Status.ERROR, 0, 0);
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

    /**
     * Retourne l'id du profil actif du joueur, ou -1 s'il n'en a pas.
     * Version {@code UUID} : c'est celle-ci qui fait l'appel base, appelable depuis un thread d'arriere-plan
     * (voir {@link DbExecutor}) puisqu'elle ne touche a aucun objet {@link Player}.
     */
    public static int getActiveProfileId(UUID playerUuid) {
        return query("SELECT active_profile FROM mc_accounts WHERE uuid = ?",
                stmt -> stmt.setString(1, playerUuid.toString()),
                rs -> rs.next() && rs.getObject(1) != null ? rs.getInt(1) : -1);
    }

    public static int getActiveProfileId(Player player) {
        return getActiveProfileId(player.getUUID());
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

    // -------------------------------------------------------------------------
    // Entreprises
    // -------------------------------------------------------------------------

    // Les tables bb_companies et bb_company_members appartiennent a l'autre mod (celui des entreprises) :
    // on ne fait que les LIRE. Une entreprise y est identifiee par son id (INT). Nous ne possedons que le lien
    // entreprise -> compte bancaire (bb_company_accounts).

    /** Id d'entreprise tel que stocke dans le TPE (texte), ou vide s'il est absent ou invalide. */
    public static OptionalInt parseCompanyId(String raw) {
        if (raw == null || !raw.trim().matches("\\d{1,9}")) return OptionalInt.empty();
        int id = Integer.parseInt(raw.trim());
        return id > 0 ? OptionalInt.of(id) : OptionalInt.empty();
    }

    /** Id d'une entreprise d'apres son nom (insensible a la casse, selon la collation de la table). */
    public static Optional<Integer> findCompanyIdByName(String name) {
        return query("SELECT id FROM bb_companies WHERE name = ?",
                stmt -> stmt.setString(1, name == null ? "" : name.trim()),
                rs -> rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty());
    }

    public static Optional<String> findCompanyName(int companyId) {
        return query("SELECT name FROM bb_companies WHERE id = ?",
                stmt -> stmt.setInt(1, companyId),
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty());
    }

    public static boolean companyExists(int companyId) {
        return query("SELECT 1 FROM bb_companies WHERE id = ?",
                stmt -> stmt.setInt(1, companyId),
                ResultSet::next);
    }

    /** Compte bancaire lie a l'entreprise, vide si elle n'en a pas encore (voir {@link #ensureCompanyAccount}). */
    public static Optional<String> findCompanyAccountNumber(int companyId) {
        return LEDGER.findCompanyAccountNumber(companyId);
    }

    /**
     * Compte bancaire de l'entreprise, cree a la volee s'il n'existe pas encore (solde nul, aucun proprietaire).
     * Toute entreprise a donc un compte des qu'on en a besoin (paiement, carte, TPE), meme si elle a ete creee
     * apres coup par l'autre mod et meme si elle n'a aucun personnel. Ne verifie PAS que l'entreprise existe :
     * c'est a l'appelant de le faire (voir {@link #companyExists}). Appel base bloquant.
     */
    public static String ensureCompanyAccount(int companyId) {
        return LEDGER.getOrCreateCompanyAccount(companyId);
    }

    /** Ids des entreprises (table de l'autre mod) qui n'ont pas encore de compte bancaire. */
    private static List<Integer> companyIdsWithoutAccount() {
        return query("SELECT c.id FROM bb_companies c LEFT JOIN bb_company_accounts a ON a.company_id = c.id "
                        + "WHERE a.company_id IS NULL ORDER BY c.id",
                stmt -> { },
                rs -> {
                    List<Integer> ids = new ArrayList<>();
                    while (rs.next()) {
                        ids.add(rs.getInt(1));
                    }
                    return ids;
                });
    }

    /** Ouvre le compte de toutes les entreprises qui n'en ont pas encore ; retourne combien ont ete ouverts. */
    public static int openMissingCompanyAccounts() {
        int opened = 0;
        for (int companyId : companyIdsWithoutAccount()) {
            ensureCompanyAccount(companyId);
            opened++;
        }
        return opened;
    }

    /**
     * Vrai si le joueur est membre de l'entreprise (bb_company_members). Version {@code UUID} : fait un appel
     * base, appelable depuis un thread d'arriere-plan. Le rang n'est pas pris en compte ici.
     */
    public static boolean isCompanyMember(UUID playerUuid, int companyId) {
        return query("SELECT 1 FROM bb_company_members WHERE company_id = ? AND player_uuid = ?",
                stmt -> {
                    stmt.setInt(1, companyId);
                    stmt.setString(2, playerUuid.toString());
                },
                ResultSet::next);
    }

    /** Verification async de l'appartenance du joueur a l'entreprise (TPE vendeur). En cas d'erreur : faux. */
    public static void worksForCompany(ServerPlayer player, int companyId, Consumer<Boolean> onResult) {
        UUID uuid = player.getUUID();
        DbExecutor.supply(() -> isCompanyMember(uuid, companyId))
                .whenCompleteAsync((result, error) -> {
                    if (!isStillOnline(player)) return;
                    if (error != null) {
                        LOGGER.error("Verification de l'appartenance a l'entreprise {} impossible pour {}",
                                companyId, player.getName().getString(), error);
                        onResult.accept(false);
                        return;
                    }
                    onResult.accept(result);
                }, player.getServer());
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

    /** Version {@code UUID}, appelable depuis un thread d'arriere-plan. */
    public static boolean hasDefaultCardPin(UUID playerUuid) {
        int profileId = getActiveProfileId(playerUuid);
        if (profileId == -1) return false;
        String defaultPin = defaultPin();
        return loadCards(profileId).stream().anyMatch(card -> PinHasher.verify(defaultPin, card.pinHash()));
    }

    public static boolean hasDefaultCardPin(Player player) {
        return hasDefaultCardPin(player.getUUID());
    }

    public static String getCardNumber(Player player) {
        int profileId = requireProfileId(player);
        List<CardRow> cards = loadCards(profileId);
        if (cards.isEmpty()) {
            throw new BankException("Carte introuvable pour le profil " + profileId);
        }
        return cards.get(0).cardNumber();
    }

    /** Version {@code UUID}, appelable depuis un thread d'arriere-plan. */
    public static boolean playerOwnsCard(UUID playerUuid, String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) return false;
        int profileId = getActiveProfileId(playerUuid);
        if (profileId == -1) return false;
        return loadCards(profileId).stream().anyMatch(card -> card.cardNumber().equals(cardNumber));
    }

    public static boolean playerOwnsCard(Player player, String cardNumber) {
        return playerOwnsCard(player.getUUID(), cardNumber);
    }

    /** Numeros de carte tenus en main (sans verifier l'appartenance), a capturer sur le thread principal. */
    public record HandCards(Optional<String> mainHand, Optional<String> offHand) {
        public boolean isEmpty() {
            return mainHand.isEmpty() && offHand.isEmpty();
        }
    }

    public static HandCards cardsInHand(Player player) {
        return new HandCards(firstCardNumber(player.getMainHandItem()), firstCardNumber(player.getOffhandItem()));
    }

    /**
     * Vrai si l'une des deux cartes capturees par {@link #cardsInHand} appartient au joueur.
     * Appelable depuis un thread d'arriere-plan (aucun objet {@link Player} n'est touche ici).
     */
    public static boolean ownsAnyOf(UUID playerUuid, HandCards cards) {
        return cards.mainHand().map(card -> playerOwnsCard(playerUuid, card)).orElse(false)
                || cards.offHand().map(card -> playerOwnsCard(playerUuid, card)).orElse(false);
    }

    private static Optional<String> firstCardNumber(ItemStack stack) {
        if (stack.getItem() != ModItems.CARD.get()) return Optional.empty();
        String cardNumber = CardItem.getCardNumber(stack);
        return cardNumber.isBlank() ? Optional.empty() : Optional.of(cardNumber);
    }

    /** Numero de compte associe a une carte, si elle existe reellement en base. */
    public static Optional<String> resolveCardAccount(String cardNumber) {
        return LEDGER.accountNumberForCard(cardNumber);
    }

    /** Version {@code UUID}, appelable depuis un thread d'arriere-plan. */
    public static PinCheck checkPin(UUID playerUuid, int pin) {
        int profileId = getActiveProfileId(playerUuid);
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

    public static PinCheck checkPin(Player player, int pin) {
        return checkPin(player.getUUID(), pin);
    }

    /**
     * Verifie le PIN d'une carte precise (et non celui de "toutes les cartes du profil"), pour l'ATM. Les tentatives
     * sont comptees par carte : un verrouillage ne touche que cette carte.
     */
    public static PinCheck checkCardPin(String cardNumber, int pin) {
        Optional<String> pinHash = cardPinHash(cardNumber);
        if (pinHash.isEmpty()) {
            return new PinCheck(PinCheck.Status.NO_CARD, 0, 0);
        }

        long locked = PIN_ATTEMPTS.remainingLockSeconds(cardNumber);
        if (locked > 0) {
            return new PinCheck(PinCheck.Status.LOCKED, 0, locked);
        }
        if (pin < 0 || pin > 9999) {
            return recordFailedPin(cardNumber);
        }

        String candidate = String.format("%04d", pin);
        if (PinHasher.verify(candidate, pinHash.get())) {
            PIN_ATTEMPTS.reset(cardNumber);
            if (PinHasher.needsUpgrade(pinHash.get())) {
                upgradePinHash(cardNumber, candidate);
            }
            return new PinCheck(PinCheck.Status.OK, 0, 0);
        }
        return recordFailedPin(cardNumber);
    }

    private static Optional<String> cardPinHash(String cardNumber) {
        return query("SELECT card_pin FROM bb_bank_cards WHERE card_number = ?",
                stmt -> stmt.setString(1, cardNumber),
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty());
    }

    private static PinCheck recordFailedPin(String key) {
        int left = PIN_ATTEMPTS.recordFailure(key, Config.PIN_MAX_ATTEMPTS, Config.PIN_LOCKOUT_SECONDS * 1000L);
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

    /** Version {@code UUID}, appelable depuis un thread d'arriere-plan. */
    public static boolean updateCardPin(UUID playerUuid, String pin) {
        if (!isValidPin(pin)) return false;
        int profileId = getActiveProfileId(playerUuid);
        if (profileId == -1) return false;
        Optional<String> account = LEDGER.findPersonalAccountNumber(profileId);
        if (account.isEmpty()) return false;

        String newHash = PinHasher.hash(pin);
        int rows = update("UPDATE bb_bank_cards SET card_pin = ? WHERE account_number = ?", stmt -> {
            stmt.setString(1, newHash);
            stmt.setString(2, account.get());
        });
        if (rows > 0) {
            // Le verrouillage du PIN est compte par carte (ATM) ET par compte (TPE) : on efface les deux.
            clearPinLocks(account.get());
        }
        return rows > 0;
    }

    /** Efface le compteur d'essais rates et le verrouillage du compte et de toutes ses cartes. */
    public static void clearPinLocks(String accountNumber) {
        PIN_ATTEMPTS.reset(accountNumber);
        for (String cardNumber : LEDGER.cardNumbersOf(accountNumber)) {
            PIN_ATTEMPTS.reset(cardNumber);
        }
    }

    public static boolean updateCardPin(Player player, String pin) {
        return updateCardPin(player.getUUID(), pin);
    }

    public static boolean resetCardPinToDefault(Player player) {
        return updateCardPin(player, defaultPin());
    }

    /**
     * Change le PIN d'une carte precise (contrairement a {@link #updateCardPin}, qui change le PIN des cartes du
     * compte personnel du profil actif). Utilise par l'ATM, pour la carte du joueur deja authentifiee par son PIN.
     */
    public static boolean updateCardPinByNumber(String cardNumber, String pin) {
        if (!isValidPin(pin) || isDefaultPin(pin)) return false;
        int rows = update("UPDATE bb_bank_cards SET card_pin = ? WHERE card_number = ?", stmt -> {
            stmt.setString(1, PinHasher.hash(pin));
            stmt.setString(2, cardNumber);
        });
        if (rows > 0) {
            PIN_ATTEMPTS.reset(cardNumber);
        }
        return rows > 0;
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

    /**
     * Reemet la carte du joueur : PIN remis par defaut, verrouillage efface, et NOUVEAU numero de carte. L'ancien
     * objet (perdu, vole, duplique) ne correspond plus a rien en base et cesse donc de fonctionner.
     * Retourne le nouveau numero.
     */
    public static String reissueCard(Player player) {
        String oldNumber = getCardNumber(player);
        if (!resetCardPinToDefault(player)) {
            throw new BankException("Impossible de reinitialiser le PIN de la carte.");
        }
        String newNumber = LEDGER.renumberCard(oldNumber)
                .orElseThrow(() -> new BankException("Impossible de renumeroter la carte bancaire."));
        PIN_ATTEMPTS.reset(oldNumber);
        if (!giveCardItemByNumber(player, newNumber)) {
            throw new BankException("Impossible de redonner la carte bancaire.");
        }
        return newNumber;
    }

    // -------------------------------------------------------------------------
    // Administration (commandes /bb_economy) : appels bloquants, reserves aux commandes
    // -------------------------------------------------------------------------

    public static TxResult adminCredit(String accountNumber, BigDecimal amount, MinecraftServer server) {
        return LEDGER.adminCredit(accountNumber, amount, currentInGameDay(server));
    }

    public static TxResult adminDebit(String accountNumber, BigDecimal amount, MinecraftServer server) {
        return LEDGER.adminDebit(accountNumber, amount, currentInGameDay(server));
    }

    /** Gele ou degele un compte ; faux si le compte n'existe pas. */
    public static boolean setAccountFrozen(String accountNumber, boolean frozen) {
        return LEDGER.setFrozen(accountNumber, frozen);
    }

    public static Optional<BankLedger.AccountInfo> accountInfo(String accountNumber) {
        return LEDGER.accountInfo(accountNumber);
    }

    public static List<String> cardNumbersOf(String accountNumber) {
        return LEDGER.cardNumbersOf(accountNumber);
    }

    public static List<BankLedger.TxRecord> recentTransactions(String accountNumber, int limit) {
        return LEDGER.recentTransactions(accountNumber, limit);
    }

    // -------------------------------------------------------------------------
    // Operations d'argent
    // -------------------------------------------------------------------------
    // Deposit/retrait/virement "sur le compte personnel du profil actif" n'existent plus ici : l'ATM
    // resout desormais toujours le compte a partir de la carte tenue en main (voir depositToAccount et
    // consorts, plus bas), y compris pour le cas normal ou cette carte est la carte personnelle du joueur.

    /**
     * Version {@code UUID}/{@code inGameDay}, appelable depuis un thread d'arriere-plan : {@code inGameDay}
     * doit avoir ete capture sur le thread principal (voir {@link #currentInGameDay}, qui touche au serveur).
     */
    public static TxResult processTpePayment(UUID buyerUuid, String companyAccount, BigDecimal amount, long inGameDay) {
        int profileId = getActiveProfileId(buyerUuid);
        if (profileId == -1) return TxResult.NO_ACCOUNT;
        return LEDGER.payTpe(profileId, companyAccount, amount, inGameDay);
    }

    public static TxResult processTpePayment(Player buyer, String companyAccount, BigDecimal amount) {
        return processTpePayment(buyer.getUUID(), companyAccount, amount, currentInGameDay(buyer));
    }

    // Ces trois operations sont les plus frequentes du mod (chaque action ATM) : seul l'appel base part
    // sur un thread d'arriere-plan (DbExecutor), tout ce qui touche a l'inventaire ou au joueur reste sur
    // le thread principal, avant l'appel (retrait des billets) et apres (callback, toujours re-hopped
    // via player.getServer()). onResult n'est jamais appele si le joueur s'est deconnecte entre-temps :
    // l'argent est deja correctement a jour en base de toute facon, inutile de toucher un joueur parti.
    //
    // Le solde est relu dans la MEME requete en arriere-plan (pas apres coup sur le thread principal) :
    // ca evite un deuxieme aller-retour SQL bloquant juste pour l'affichage.

    /** Resultat d'une operation d'argent, avec le solde a jour du compte concerne. */
    public record MoneyResult(TxResult result, BigDecimal balance) {
    }

    /**
     * Depot sur un compte quelconque (celui de la carte tenue en main a l'ATM), pas forcement le compte
     * personnel du joueur. Les billets sont retires AVANT le credit (sur le thread principal), et rendus
     * si le depot est refuse ou si la base est indisponible.
     */
    public static void depositToAccount(ServerPlayer player, String accountNumber, BigDecimal amount, Consumer<MoneyResult> onResult) {
        if (!Money.isValid(amount) || !Money.isWhole(amount)) {
            onResult.accept(new MoneyResult(TxResult.INVALID_AMOUNT, accountBalanceOrZero(accountNumber)));
            return;
        }
        int bills = Money.toBills(amount);
        if (countBills(player) < bills) {
            onResult.accept(new MoneyResult(TxResult.NOT_ENOUGH_CASH, accountBalanceOrZero(accountNumber)));
            return;
        }
        removeBills(player, bills);
        syncInventory(player);

        long inGameDay = currentInGameDay(player);
        BigDecimal limit = dailyLimit(Config.DAILY_DEPOSIT_LIMIT);

        DbExecutor.supply(() -> {
            TxResult result = LEDGER.deposit(accountNumber, amount, limit, inGameDay);
            return new MoneyResult(result, LEDGER.findBalance(accountNumber).orElse(BigDecimal.ZERO));
        }).whenCompleteAsync((outcome, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                outcome = new MoneyResult(logAndConvert(error, player, "depot"), BigDecimal.ZERO);
            }
            if (!outcome.result().isOk()) {
                addMoneyStacks(player, bills);
                syncInventory(player);
            }
            onResult.accept(outcome);
        }, player.getServer());
    }

    /** Retrait sur un compte quelconque (celui de la carte tenue en main a l'ATM). */
    public static void withdrawFromAccount(ServerPlayer player, String accountNumber, BigDecimal amount, Consumer<MoneyResult> onResult) {
        if (!Money.isValid(amount) || !Money.isWhole(amount)) {
            onResult.accept(new MoneyResult(TxResult.INVALID_AMOUNT, accountBalanceOrZero(accountNumber)));
            return;
        }

        long inGameDay = currentInGameDay(player);
        BigDecimal limit = dailyLimit(Config.DAILY_WITHDRAW_LIMIT);

        DbExecutor.supply(() -> {
            TxResult result = LEDGER.withdraw(accountNumber, amount, limit, inGameDay);
            return new MoneyResult(result, LEDGER.findBalance(accountNumber).orElse(BigDecimal.ZERO));
        }).whenCompleteAsync((outcome, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                outcome = new MoneyResult(logAndConvert(error, player, "retrait"), BigDecimal.ZERO);
            }
            if (outcome.result().isOk()) {
                addMoneyStacks(player, Money.toBills(amount));
                syncInventory(player);
            }
            onResult.accept(outcome);
        }, player.getServer());
    }

    /** Virement depuis un compte quelconque (celui de la carte tenue en main a l'ATM). Ne touche pas a l'inventaire. */
    public static void transferFromAccount(ServerPlayer player, String fromAccountNumber, String toAccountNumber,
                                            BigDecimal amount, Consumer<MoneyResult> onResult) {
        long inGameDay = currentInGameDay(player);
        BigDecimal limit = dailyLimit(Config.DAILY_TRANSFER_LIMIT);

        DbExecutor.supply(() -> {
            TxResult result = LEDGER.transferFromAccount(fromAccountNumber, toAccountNumber, amount, limit, inGameDay);
            return new MoneyResult(result, LEDGER.findBalance(fromAccountNumber).orElse(BigDecimal.ZERO));
        }).whenCompleteAsync((outcome, error) -> {
            if (!isStillOnline(player)) return;
            onResult.accept(error != null
                    ? new MoneyResult(logAndConvert(error, player, "virement"), BigDecimal.ZERO)
                    : outcome);
        }, player.getServer());
    }

    /** Issue d'un paiement TPE : distingue "carte absente" et "entreprise introuvable" d'un refus bancaire normal. */
    public enum TpeOutcome { OK, NOT_HOLDING_CARD, COMPANY_NOT_FOUND, REFUSED }

    public record TpePaymentResult(TpeOutcome outcome, TxResult txResult) {
        public String message() {
            return switch (outcome) {
                case OK -> txResult.message();
                case NOT_HOLDING_CARD -> "Vous devez garder votre carte bancaire en main pour payer.";
                case COMPANY_NOT_FOUND -> "Compte entreprise introuvable.";
                case REFUSED -> switch (txResult) {
                    case INSUFFICIENT_FUNDS -> "Paiement refuse : solde insuffisant.";
                    case SAME_ACCOUNT -> "Vous ne pouvez pas payer votre propre compte.";
                    default -> "Paiement refuse : " + txResult.message();
                };
            };
        }
    }

    /**
     * Paiement TPE async, en un seul aller-retour base : verifie la carte en main, resout le compte de
     * l'entreprise et effectue le paiement dans la MEME requete plutot qu'en trois appels chaines (chacun
     * rechargerait inutilement le thread principal entre deux). Ne touche pas a l'inventaire (le montant
     * en attente reste sur le bloc TPE, mis a jour par l'appelant apres coup).
     */
    public static void payAtTpe(ServerPlayer buyer, HandCards cards, int companyId, BigDecimal amount,
                                 Consumer<TpePaymentResult> onResult) {
        UUID uuid = buyer.getUUID();
        long inGameDay = currentInGameDay(buyer);

        DbExecutor.supply(() -> {
            if (!ownsAnyOf(uuid, cards)) {
                return new TpePaymentResult(TpeOutcome.NOT_HOLDING_CARD, TxResult.NO_ACCOUNT);
            }
            if (!companyExists(companyId)) {
                return new TpePaymentResult(TpeOutcome.COMPANY_NOT_FOUND, TxResult.NO_ACCOUNT);
            }
            // L'entreprise existe : si elle n'a pas encore de compte (creee apres coup, ou sans personnel), on
            // l'ouvre ici plutot que de refuser le paiement.
            String companyAccount = ensureCompanyAccount(companyId);
            TxResult result = processTpePayment(uuid, companyAccount, amount, inGameDay);
            return new TpePaymentResult(result.isOk() ? TpeOutcome.OK : TpeOutcome.REFUSED, result);
        }).whenCompleteAsync((outcome, error) -> {
            if (!isStillOnline(buyer)) return;
            if (error != null) {
                onResult.accept(new TpePaymentResult(TpeOutcome.REFUSED, logAndConvert(error, buyer, "paiement TPE")));
                return;
            }
            onResult.accept(outcome);
        }, buyer.getServer());
    }

    /** Resultat d'une verification de PIN, avec le solde du compte si elle a reussi (sinon zero). */
    public record PinResult(PinCheck check, BigDecimal balance) {
    }

    /**
     * Verification de PIN async, carte precise (ATM). {@code accountNumber} (deja connu de l'ATM depuis
     * son ouverture) sert uniquement a lire le solde a afficher en cas de succes, dans la meme requete.
     */
    public static void checkCardPin(ServerPlayer player, String cardNumber, String accountNumber, int pin, Consumer<PinResult> onResult) {
        DbExecutor.supply(() -> {
            PinCheck check = checkCardPin(cardNumber, pin);
            BigDecimal balance = check.isOk() ? LEDGER.findBalance(accountNumber).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
            return new PinResult(check, balance);
        }).whenCompleteAsync((outcome, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Erreur pendant la verification du PIN de la carte {} pour {}",
                        cardNumber, player.getName().getString(), error);
                onResult.accept(new PinResult(PinCheck.error(), BigDecimal.ZERO));
                return;
            }
            onResult.accept(outcome);
        }, player.getServer());
    }

    /** Verification de PIN async, compte personnel (TPE acheteur), avec le solde en cas de succes. */
    public static void checkPin(ServerPlayer player, int pin, Consumer<PinResult> onResult) {
        UUID uuid = player.getUUID();
        DbExecutor.supply(() -> {
            PinCheck check = checkPin(uuid, pin);
            BigDecimal balance = check.isOk() ? LEDGER.findPersonalBalance(getActiveProfileId(uuid)).orElse(BigDecimal.ZERO) : BigDecimal.ZERO;
            return new PinResult(check, balance);
        }).whenCompleteAsync((outcome, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Erreur pendant la verification du PIN de {}", player.getName().getString(), error);
                onResult.accept(new PinResult(PinCheck.error(), BigDecimal.ZERO));
                return;
            }
            onResult.accept(outcome);
        }, player.getServer());
    }


    /**
     * Ce qu'il faut pour ouvrir l'ATM : la carte du joueur, son compte personnel, et les entreprises dont il
     * est le patron (donc dont il peut utiliser le compte en plus du sien).
     */
    public record AtmSession(String cardNumber, String personalAccountNumber, List<CompanyChoice> companies) {
    }

    /**
     * Ouverture de l'ATM, en un seul aller-retour base : trouve, parmi les cartes tenues en main, celle qui
     * appartient bien au joueur, son compte personnel, et les entreprises dont il est patron. Vide si aucune
     * carte en main ne lui appartient. {@code cards} doit avoir ete capture sur le thread principal.
     */
    public static void resolveAtmSession(ServerPlayer player, HandCards cards, Consumer<Optional<AtmSession>> onResult) {
        if (cards.isEmpty()) {
            onResult.accept(Optional.empty());
            return;
        }
        UUID uuid = player.getUUID();
        DbExecutor.supply(() -> {
            Optional<String> owned = cards.mainHand().filter(card -> playerOwnsCard(uuid, card))
                    .or(() -> cards.offHand().filter(card -> playerOwnsCard(uuid, card)));
            if (owned.isEmpty()) {
                return Optional.<AtmSession>empty();
            }
            Optional<String> account = resolveCardAccount(owned.get());
            if (account.isEmpty()) {
                return Optional.<AtmSession>empty();
            }
            return Optional.of(new AtmSession(owned.get(), account.get(), findOwnedCompanies(uuid)));
        }).whenCompleteAsync((result, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Ouverture de l'ATM impossible pour {}", player.getName().getString(), error);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(result);
        }, player.getServer());
    }

    /**
     * Entreprises dont le joueur est le patron. Ces tables appartiennent a l'autre mod : si elles n'existent
     * pas (encore), le joueur n'a simplement pas d'entreprise et l'ATM reste utilisable avec son compte perso.
     */
    private static List<CompanyChoice> findOwnedCompanies(UUID playerUuid) {
        try {
            return query("SELECT id, name FROM bb_companies WHERE owner_uuid = ? ORDER BY name",
                    stmt -> stmt.setString(1, playerUuid.toString()),
                    rs -> {
                        List<CompanyChoice> companies = new ArrayList<>();
                        while (rs.next()) {
                            companies.add(new CompanyChoice(rs.getInt(1), rs.getString(2)));
                        }
                        return companies;
                    });
        } catch (BankException e) {
            LOGGER.debug("Lecture des entreprises du joueur impossible (table des entreprises absente ?) : {}", e.getMessage());
            return List.of();
        }
    }

    /** Compte d'entreprise choisi a l'ATM, avec son solde. */
    public record AccountSelection(String accountNumber, BigDecimal balance) {
    }

    /**
     * Selectionne le compte d'une entreprise a l'ATM. Reverifie en base que le joueur en est bien le patron
     * (la liste proposee a l'ouverture peut etre perimee), ouvre le compte s'il n'existe pas encore et lit son
     * solde. Vide si le joueur n'est pas le patron.
     */
    public static void selectCompanyAccount(ServerPlayer player, int companyId, Consumer<Optional<AccountSelection>> onResult) {
        UUID uuid = player.getUUID();
        DbExecutor.supply(() -> {
            boolean isOwner = query("SELECT 1 FROM bb_companies WHERE id = ? AND owner_uuid = ?",
                    stmt -> {
                        stmt.setInt(1, companyId);
                        stmt.setString(2, uuid.toString());
                    },
                    ResultSet::next);
            if (!isOwner) {
                return Optional.<AccountSelection>empty();
            }
            String account = ensureCompanyAccount(companyId);
            return Optional.of(new AccountSelection(account, LEDGER.findBalance(account).orElse(BigDecimal.ZERO)));
        }).whenCompleteAsync((result, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Selection du compte de l'entreprise {} impossible pour {}",
                        companyId, player.getName().getString(), error);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(result);
        }, player.getServer());
    }

    /** Solde d'un compte, lu hors du thread principal (retour au compte personnel a l'ATM). */
    public static void loadBalance(ServerPlayer player, String accountNumber, Consumer<BigDecimal> onResult) {
        DbExecutor.supply(() -> LEDGER.findBalance(accountNumber).orElse(BigDecimal.ZERO))
                .whenCompleteAsync((result, error) -> {
                    if (!isStillOnline(player)) return;
                    if (error != null) {
                        LOGGER.error("Lecture du solde impossible pour le compte {}", accountNumber, error);
                        onResult.accept(BigDecimal.ZERO);
                        return;
                    }
                    onResult.accept(result);
                }, player.getServer());
    }

    /**
     * Verification async d'appartenance d'une des deux cartes en main (voir {@link #cardsInHand}), pour
     * l'ouverture du TPE et le paiement. {@code cards} doit avoir ete capture sur le thread principal.
     */
    public static void ownsAnyOf(ServerPlayer player, HandCards cards, Consumer<Boolean> onResult) {
        if (cards.isEmpty()) {
            onResult.accept(false);
            return;
        }
        UUID uuid = player.getUUID();
        DbExecutor.supply(() -> ownsAnyOf(uuid, cards))
                .whenCompleteAsync((result, error) -> {
                    if (!isStillOnline(player)) return;
                    if (error != null) {
                        LOGGER.error("Verification de la carte impossible pour {}", player.getName().getString(), error);
                        onResult.accept(false);
                        return;
                    }
                    onResult.accept(result);
                }, player.getServer());
    }

    /** Changement async du PIN d'une carte precise (ATM, carte deja authentifiee). */
    public static void updateCardPinByNumber(ServerPlayer player, String cardNumber, String pin, Consumer<Boolean> onResult) {
        DbExecutor.supply(() -> updateCardPinByNumber(cardNumber, pin))
                .whenCompleteAsync((result, error) -> {
                    if (!isStillOnline(player)) return;
                    if (error != null) {
                        LOGGER.error("Changement de PIN impossible pour la carte {} ({})", cardNumber, player.getName().getString(), error);
                        onResult.accept(false);
                        return;
                    }
                    onResult.accept(result);
                }, player.getServer());
    }

    /** Resultat de l'initialisation du PIN personnel (ecran de premiere connexion, hors ATM). */
    public enum InitPinResult { NOT_REQUIRED, SAVED, FAILED, ERROR }

    /** Version async de l'ancien flux "definir mon PIN personnel" (hors ATM, PIN encore par defaut). */
    public static void initPersonalPin(ServerPlayer player, String pin, Consumer<InitPinResult> onResult) {
        UUID uuid = player.getUUID();
        DbExecutor.supply(() -> {
            if (!hasDefaultCardPin(uuid)) return InitPinResult.NOT_REQUIRED;
            return updateCardPin(uuid, pin) ? InitPinResult.SAVED : InitPinResult.FAILED;
        }).whenCompleteAsync((result, error) -> {
            if (!isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Initialisation du PIN impossible pour {}", player.getName().getString(), error);
                onResult.accept(InitPinResult.ERROR);
                return;
            }
            onResult.accept(result);
        }, player.getServer());
    }

    private static TxResult logAndConvert(Throwable error, Player player, String action) {
        LOGGER.error("Erreur pendant un {} pour {}", action, player.getName().getString(), error);
        return TxResult.SERVICE_UNAVAILABLE;
    }

    /**
     * Vrai si le joueur est toujours connecte avec cette meme instance. A verifier avant de toucher a son
     * inventaire ou de lui envoyer un paquet depuis une continuation async : l'argent, lui, est de toute
     * facon deja correctement a jour en base (ou refuse), qu'on touche au joueur ensuite ou non.
     */
    private static boolean isStillOnline(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    /** Solde d'un compte quelconque pour l'affichage : 0 si introuvable ou la base indisponible. */
    public static BigDecimal accountBalanceOrZero(String accountNumber) {
        try {
            return LEDGER.findBalance(accountNumber).orElse(BigDecimal.ZERO);
        } catch (BankException e) {
            LOGGER.error("Lecture du solde impossible pour le compte {}", accountNumber, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Ouvre le compte bancaire d'une entreprise deja creee par l'autre mod (commande admin). Le compte n'a pas
     * de proprietaire : l'entreprise elle-meme, ses membres et ses rangs ne sont pas geres ici.
     *
     * @throws IllegalArgumentException si l'entreprise n'existe pas
     * @throws IllegalStateException    si l'entreprise a deja un compte
     */
    public static String openCompanyAccount(int companyId) {
        if (!companyExists(companyId)) {
            throw new IllegalArgumentException("Entreprise introuvable (id " + companyId + ").");
        }
        return LEDGER.createCompanyAccount(companyId, BigDecimal.ZERO)
                .orElseThrow(() -> new IllegalStateException("Cette entreprise a deja un compte bancaire."));
    }

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    public static ItemStack createCardItem(String cardNumber) {
        ItemStack stack = new ItemStack(ModItems.CARD.get());
        stack.getOrCreateTag().putString(CardItem.TAG_CARD_NUMBER, cardNumber);
        return stack;
    }

    /** Redonne une carte : refuse si elle n'appartient pas au profil actif du joueur. */
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

    private static BigDecimal dailyLimit(double configuredValue) {
        return BigDecimal.valueOf(configuredValue).setScale(2, RoundingMode.HALF_UP);
    }

    /** Jour in-game de l'overworld : identique quelle que soit la dimension ou se trouve le joueur. */
    private static long currentInGameDay(Player player) {
        MinecraftServer server = player.getServer();
        long dayTime = server != null ? server.overworld().getDayTime() : player.level().getDayTime();
        return dayTime / 24000L;
    }

    private static long currentInGameDay(MinecraftServer server) {
        return server.overworld().getDayTime() / 24000L;
    }
}
