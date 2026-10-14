package org.bb.bb_economy.database;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bb.bb_economy.Config;
import org.bb.bb_economy.init.ModItems;
import org.bb.bb_economy.item.CardItem;
import org.bb.bb_economy.perms.JobService;
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

    /**
     * Vrai si le joueur est salarie de cette entreprise, c'est-a-dire membre du groupe LuckPerms du meme
     * nom (voir {@link JobService}). Sans LuckPerms installe, personne n'est jamais reconnu comme salarie.
     */
    public static boolean playerWorksForCompany(Player player, String companyId) {
        return JobService.isMemberOfCompany(player.getUUID(), normalizeCompanyId(companyId));
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

    /**
     * Numero d'une carte bancaire tenue en main (principale ou secondaire), sans verifier a qui elle
     * appartient. Sert uniquement a ouvrir l'ATM : le compte concret est resolu ensuite via
     * {@link #resolveCardAccount(String)}, et savoir operer dessus reste soumis au PIN de cette carte.
     * Une carte d'entreprise n'est pas liee a un seul profil (plusieurs employes peuvent en avoir une
     * copie), donc {@link #playerOwnsCard} ne peut pas servir de filtre ici.
     */
    public static Optional<String> genuineCardInHand(Player player) {
        return firstCardNumber(player.getMainHandItem()).or(() -> firstCardNumber(player.getOffhandItem()));
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
     * Verifie le PIN d'une carte precise (pas "toutes les cartes du profil"), pour l'ATM quand la carte
     * en main est celle d'une entreprise. Les tentatives sont comptees par carte : bloquer une carte
     * d'entreprise ne bloque pas les autres cartes du meme compte.
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
            PIN_ATTEMPTS.reset(account.get());
        }
        return rows > 0;
    }

    public static boolean updateCardPin(Player player, String pin) {
        return updateCardPin(player.getUUID(), pin);
    }

    public static boolean resetCardPinToDefault(Player player) {
        return updateCardPin(player, defaultPin());
    }

    /**
     * Change le PIN d'une carte precise (contrairement a {@link #updateCardPin}, qui changerait le PIN
     * de TOUTES les cartes du compte personnel). Utilise par l'ATM : on ne modifie que la carte tenue en
     * main, pour que deux cartes d'un meme compte entreprise gardent chacune leur propre PIN.
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
    public static void payAtTpe(ServerPlayer buyer, HandCards cards, String companyId, BigDecimal amount,
                                 Consumer<TpePaymentResult> onResult) {
        UUID uuid = buyer.getUUID();
        long inGameDay = currentInGameDay(buyer);

        DbExecutor.supply(() -> {
            if (!ownsAnyOf(uuid, cards)) {
                return new TpePaymentResult(TpeOutcome.NOT_HOLDING_CARD, TxResult.NO_ACCOUNT);
            }
            String normalized = normalizeCompanyId(companyId);
            if (!companyExists(normalized)) {
                return new TpePaymentResult(TpeOutcome.COMPANY_NOT_FOUND, TxResult.NO_ACCOUNT);
            }
            String companyAccount = getCompanyAccountNumber(normalized);
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

    /** Resolution async du compte associe a une carte (ouverture de l'ATM). */
    public static void resolveCardAccount(ServerPlayer player, String cardNumber, Consumer<Optional<String>> onResult) {
        DbExecutor.supply(() -> resolveCardAccount(cardNumber))
                .whenCompleteAsync((result, error) -> {
                    if (!isStillOnline(player)) return;
                    if (error != null) {
                        LOGGER.error("Resolution du compte de la carte {} impossible pour {}",
                                cardNumber, player.getName().getString(), error);
                        onResult.accept(Optional.empty());
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
     * Cree le compte bancaire d'une entreprise, rattache administrativement a un joueur (le "proprietaire").
     * Le lien salarie/entreprise ne vit pas ici : il faut creer separement un groupe LuckPerms du meme nom
     * (insensible a la casse) que {@code companyId} et y ajouter les salaries (voir {@link JobService}).
     */
    public static String createCompanyAccount(String companyId, Player owner) {
        int profileId = requireProfileId(owner);
        String normalized = normalizeCompanyId(companyId);
        return LEDGER.createCompanyAccount(normalized, profileId, BigDecimal.ZERO)
                .orElseThrow(() -> new IllegalStateException("Une entreprise existe deja avec cet identifiant : " + normalized));
    }

    /**
     * Emet une nouvelle carte pour le compte d'une entreprise (PIN par defaut) et la remet au joueur cible.
     * Plusieurs cartes peuvent coexister pour la meme entreprise (patron, gerant...), chacune avec son PIN.
     */
    public static String issueCompanyCard(String companyId, Player recipient) {
        String accountNumber = getCompanyAccountNumber(companyId);
        String cardNumber = LEDGER.issueCard(accountNumber, PinHasher.hash(defaultPin()))
                .orElseThrow(() -> new BankException("Compte entreprise introuvable : " + accountNumber));
        giveRawCardItem(recipient, cardNumber);
        return cardNumber;
    }

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    public static ItemStack createCardItem(String cardNumber) {
        ItemStack stack = new ItemStack(ModItems.CARD.get());
        stack.getOrCreateTag().putString(CardItem.TAG_CARD_NUMBER, cardNumber);
        return stack;
    }

    /** Redonne une carte PERSONNELLE : refuse si elle n'appartient pas au profil actif du joueur. */
    public static boolean giveCardItemByNumber(Player player, String cardNumber) {
        if (!playerOwnsCard(player, cardNumber)) return false;
        giveRawCardItem(player, cardNumber);
        return true;
    }

    /**
     * Remet physiquement une carte au joueur, sans verifier l'appartenance. Une carte d'entreprise n'est
     * pas liee a un seul profil (voir {@link #issueCompanyCard}) : c'est la possession de l'objet et le
     * PIN, pas une verification de propriete, qui protegent son usage.
     */
    private static void giveRawCardItem(Player player, String cardNumber) {
        ItemStack cardStack = createCardItem(cardNumber);
        if (!player.getInventory().add(cardStack)) {
            player.drop(cardStack, false);
        }
        markCardItemGiven(cardNumber, player.getUUID().toString());
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
