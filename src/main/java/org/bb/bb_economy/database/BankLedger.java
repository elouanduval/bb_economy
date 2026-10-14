package org.bb.bb_economy.database;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Coeur monetaire : toutes les operations qui font bouger de l'argent, sans aucune dependance a Minecraft.
 *
 * Garanties :
 * - chaque operation est une transaction unique ; les comptes concernes sont verrouilles (FOR UPDATE),
 *   toujours dans l'ordre des numeros de compte pour eviter les deadlocks ;
 * - les plafonds journaliers sont verifies dans la meme transaction, sous verrou, donc impossibles a depasser
 *   par des requetes simultanees ;
 * - un deadlock detecte par le serveur SQL est rejoue automatiquement ;
 * - un refus metier (solde, plafond...) retourne un TxResult et n'ecrit rien.
 */
public final class BankLedger {

    public static final String TYPE_DEPOSIT = "ATM_DEPOSIT";
    public static final String TYPE_WITHDRAW = "ATM_WITHDRAW";
    public static final String TYPE_TRANSFER = "TRANSFER";
    public static final String TYPE_TPE = "TPE";
    public static final String TYPE_INITIAL = "INITIAL";

    public static final String PERSONAL = "personal";

    private static final int MAX_ATTEMPTS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    private interface TxBody<T> {
        T run(Connection conn) throws SQLException;
    }

    public record Created(String accountNumber, String cardNumber) {
    }

    private record Balance(String accountNumber, BigDecimal balance) {
    }

    private final ConnectionSource source;

    public BankLedger(ConnectionSource source) {
        this.source = source;
    }

    // -------------------------------------------------------------------------
    // Lectures
    // -------------------------------------------------------------------------

    public Optional<String> findPersonalAccountNumber(int profileId) {
        return inTransaction(conn -> Optional.ofNullable(personalAccountNumber(conn, profileId)));
    }

    public Optional<BigDecimal> findPersonalBalance(int profileId) {
        return inTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT account_balance FROM bb_bank_accounts WHERE owner = ? AND account_type = ?")) {
                stmt.setInt(1, profileId);
                stmt.setString(2, PERSONAL);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.<BigDecimal>empty();
                }
            }
        });
    }

    /** Numero de compte associe a une carte precise (personnelle ou entreprise), quel qu'en soit le type. */
    public Optional<String> accountNumberForCard(String cardNumber) {
        return inTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT account_number FROM bb_bank_cards WHERE card_number = ?")) {
                stmt.setString(1, cardNumber);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    /** Solde d'un compte quelconque, identifie par son numero. */
    public Optional<BigDecimal> findBalance(String accountNumber) {
        return inTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT account_balance FROM bb_bank_accounts WHERE account_number = ?")) {
                stmt.setString(1, accountNumber);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getBigDecimal(1)) : Optional.<BigDecimal>empty();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Creation de compte
    // -------------------------------------------------------------------------

    /**
     * Cree le compte personnel et sa carte. Vide si le profil a deja un compte personnel.
     * Le verrou sur la ligne du profil serialise deux creations simultanees pour le meme profil.
     */
    public Optional<Created> createPersonalAccount(int profileId, String pinHash, BigDecimal startingBalance, long inGameDay) {
        if (startingBalance == null || startingBalance.signum() < 0 || startingBalance.scale() > 2
                || startingBalance.compareTo(Money.MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Solde de depart invalide : " + startingBalance);
        }
        BigDecimal initial = startingBalance.setScale(2);

        return inTransaction(conn -> {
            try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM profiles WHERE id = ? FOR UPDATE")) {
                lock.setInt(1, profileId);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Profil introuvable : " + profileId);
                    }
                }
            }
            if (personalAccountNumber(conn, profileId) != null) {
                return Optional.<Created>empty();
            }

            String accountNumber = uniqueIdentifier(conn, "bb_bank_accounts", "account_number", "ACC-");
            String cardNumber = uniqueIdentifier(conn, "bb_bank_cards", "card_number", "CARD");

            try (PreparedStatement insertAccount = conn.prepareStatement(
                    "INSERT INTO bb_bank_accounts (account_number, account_type, account_balance, owner) VALUES (?, ?, ?, ?)");
                 PreparedStatement insertCard = conn.prepareStatement(
                         "INSERT INTO bb_bank_cards (card_number, card_pin, account_number) VALUES (?, ?, ?)")) {
                insertAccount.setString(1, accountNumber);
                insertAccount.setString(2, PERSONAL);
                insertAccount.setBigDecimal(3, initial);
                insertAccount.setInt(4, profileId);
                insertAccount.executeUpdate();

                insertCard.setString(1, cardNumber);
                insertCard.setString(2, pinHash);
                insertCard.setString(3, accountNumber);
                insertCard.executeUpdate();
            }
            if (initial.signum() > 0) {
                logTransaction(conn, TYPE_INITIAL, initial, null, accountNumber, inGameDay);
            }
            return Optional.of(new Created(accountNumber, cardNumber));
        });
    }

    /**
     * Cree le compte bancaire d'une entreprise, rattache a un profil "proprietaire" pour la tracabilite.
     * Vide si {@code companyId} existe deja. Contrairement au compte personnel, aucune carte n'est creee :
     * qui a le droit d'operer sur ce compte est determine ailleurs (LuckPerms), pas par bb_economy.
     */
    public Optional<String> createCompanyAccount(String companyId, int ownerProfileId, BigDecimal startingBalance) {
        if (startingBalance == null || startingBalance.signum() < 0 || startingBalance.scale() > 2
                || startingBalance.compareTo(Money.MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Solde de depart invalide : " + startingBalance);
        }
        BigDecimal initial = startingBalance.setScale(2);

        return inTransaction(conn -> {
            try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM profiles WHERE id = ? FOR UPDATE")) {
                lock.setInt(1, ownerProfileId);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Profil introuvable : " + ownerProfileId);
                    }
                }
            }
            try (PreparedStatement checkCompany = conn.prepareStatement("SELECT 1 FROM bb_companies WHERE company_id = ?")) {
                checkCompany.setString(1, companyId);
                try (ResultSet rs = checkCompany.executeQuery()) {
                    if (rs.next()) {
                        return Optional.<String>empty();
                    }
                }
            }

            String accountNumber = uniqueIdentifier(conn, "bb_bank_accounts", "account_number", "ACC-");

            try (PreparedStatement insertAccount = conn.prepareStatement(
                    "INSERT INTO bb_bank_accounts (account_number, account_type, account_balance, owner) VALUES (?, 'company', ?, ?)");
                 PreparedStatement insertCompany = conn.prepareStatement(
                         "INSERT INTO bb_companies (company_id, account_number) VALUES (?, ?)")) {
                insertAccount.setString(1, accountNumber);
                insertAccount.setBigDecimal(2, initial);
                insertAccount.setInt(3, ownerProfileId);
                insertAccount.executeUpdate();

                insertCompany.setString(1, companyId);
                insertCompany.setString(2, accountNumber);
                insertCompany.executeUpdate();
            }
            if (initial.signum() > 0) {
                logTransaction(conn, TYPE_INITIAL, initial, null, accountNumber, 0L);
            }
            return Optional.of(accountNumber);
        });
    }

    /**
     * Ajoute une carte supplementaire a un compte deja existant (personnel ou entreprise), avec le PIN fourni.
     * Contrairement a la carte creee avec un compte personnel, plusieurs cartes peuvent partager le meme
     * compte (ex : une entreprise dont le patron et un gerant ont chacun leur propre carte et leur propre PIN).
     * Vide si le compte n'existe pas.
     */
    public Optional<String> issueCard(String accountNumber, String pinHash) {
        return inTransaction(conn -> {
            try (PreparedStatement checkAccount = conn.prepareStatement(
                    "SELECT 1 FROM bb_bank_accounts WHERE account_number = ?")) {
                checkAccount.setString(1, accountNumber);
                try (ResultSet rs = checkAccount.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<String>empty();
                    }
                }
            }
            String cardNumber = uniqueIdentifier(conn, "bb_bank_cards", "card_number", "CARD");
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO bb_bank_cards (card_number, card_pin, account_number) VALUES (?, ?, ?)")) {
                insert.setString(1, cardNumber);
                insert.setString(2, pinHash);
                insert.setString(3, accountNumber);
                insert.executeUpdate();
            }
            return Optional.of(cardNumber);
        });
    }

    // -------------------------------------------------------------------------
    // Especes
    // -------------------------------------------------------------------------

    /** Credite le compte personnel (les billets doivent deja avoir ete retires du joueur). */
    public TxResult deposit(int profileId, BigDecimal amount, BigDecimal dailyLimit, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> {
            Balance account = lockPersonalAccount(conn, profileId);
            if (account == null) {
                return TxResult.NO_ACCOUNT;
            }
            BigDecimal alreadyDone = dailyTotal(conn, TYPE_DEPOSIT, inGameDay, "account_target", account.accountNumber());
            if (alreadyDone.add(amount).compareTo(dailyLimit) > 0) {
                return TxResult.LIMIT_REACHED;
            }
            BigDecimal newBalance = account.balance().add(amount);
            if (newBalance.compareTo(Money.MAX_AMOUNT) > 0) {
                return TxResult.BALANCE_OVERFLOW;
            }
            updateBalance(conn, account.accountNumber(), newBalance);
            logTransaction(conn, TYPE_DEPOSIT, amount, null, account.accountNumber(), inGameDay);
            return TxResult.OK;
        });
    }

    /** Debite le compte personnel (les billets doivent etre remis au joueur si le resultat est OK). */
    public TxResult withdraw(int profileId, BigDecimal amount, BigDecimal dailyLimit, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> {
            Balance account = lockPersonalAccount(conn, profileId);
            if (account == null) {
                return TxResult.NO_ACCOUNT;
            }
            BigDecimal alreadyDone = dailyTotal(conn, TYPE_WITHDRAW, inGameDay, "account_origin", account.accountNumber());
            if (alreadyDone.add(amount).compareTo(dailyLimit) > 0) {
                return TxResult.LIMIT_REACHED;
            }
            if (account.balance().compareTo(amount) < 0) {
                return TxResult.INSUFFICIENT_FUNDS;
            }
            updateBalance(conn, account.accountNumber(), account.balance().subtract(amount));
            logTransaction(conn, TYPE_WITHDRAW, amount, account.accountNumber(), null, inGameDay);
            return TxResult.OK;
        });
    }

    /**
     * Depot sur un compte quelconque identifie par son numero (utilise par l'ATM quand la carte en main
     * appartient a une entreprise plutot qu'au compte personnel du joueur).
     */
    public TxResult deposit(String accountNumber, BigDecimal amount, BigDecimal dailyLimit, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> {
            BigDecimal balance = lockAccount(conn, accountNumber);
            if (balance == null) {
                return TxResult.NO_ACCOUNT;
            }
            BigDecimal alreadyDone = dailyTotal(conn, TYPE_DEPOSIT, inGameDay, "account_target", accountNumber);
            if (alreadyDone.add(amount).compareTo(dailyLimit) > 0) {
                return TxResult.LIMIT_REACHED;
            }
            BigDecimal newBalance = balance.add(amount);
            if (newBalance.compareTo(Money.MAX_AMOUNT) > 0) {
                return TxResult.BALANCE_OVERFLOW;
            }
            updateBalance(conn, accountNumber, newBalance);
            logTransaction(conn, TYPE_DEPOSIT, amount, null, accountNumber, inGameDay);
            return TxResult.OK;
        });
    }

    /** Retrait sur un compte quelconque identifie par son numero (voir {@link #deposit(String, BigDecimal, BigDecimal, long)}). */
    public TxResult withdraw(String accountNumber, BigDecimal amount, BigDecimal dailyLimit, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> {
            BigDecimal balance = lockAccount(conn, accountNumber);
            if (balance == null) {
                return TxResult.NO_ACCOUNT;
            }
            BigDecimal alreadyDone = dailyTotal(conn, TYPE_WITHDRAW, inGameDay, "account_origin", accountNumber);
            if (alreadyDone.add(amount).compareTo(dailyLimit) > 0) {
                return TxResult.LIMIT_REACHED;
            }
            if (balance.compareTo(amount) < 0) {
                return TxResult.INSUFFICIENT_FUNDS;
            }
            updateBalance(conn, accountNumber, balance.subtract(amount));
            logTransaction(conn, TYPE_WITHDRAW, amount, accountNumber, null, inGameDay);
            return TxResult.OK;
        });
    }

    // -------------------------------------------------------------------------
    // Virements
    // -------------------------------------------------------------------------

    /** Virement du compte personnel d'un joueur vers un numero de compte, soumis au plafond journalier. */
    public TxResult transfer(int profileId, String toAccount, BigDecimal amount, BigDecimal dailyLimit, long inGameDay) {
        return moveFromPersonal(profileId, toAccount, amount, TYPE_TRANSFER, dailyLimit, inGameDay);
    }

    /** Paiement TPE : compte personnel de l'acheteur vers le compte de l'entreprise, sans plafond. */
    public TxResult payTpe(int profileId, String companyAccount, BigDecimal amount, long inGameDay) {
        return moveFromPersonal(profileId, companyAccount, amount, TYPE_TPE, null, inGameDay);
    }

    /** Mouvement generique entre deux comptes quelconques, sans plafond journalier (usage administratif). */
    public TxResult transferBetweenAccounts(String fromAccount, String toAccount, BigDecimal amount, String type, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> moveFunds(conn, fromAccount, toAccount, amount, type, null, inGameDay));
    }

    /**
     * Virement depuis un compte quelconque identifie par son numero, soumis au plafond journalier
     * (utilise par l'ATM quand la carte en main appartient a une entreprise).
     */
    public TxResult transferFromAccount(String fromAccount, String toAccount, BigDecimal amount, BigDecimal dailyLimit, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> moveFunds(conn, fromAccount, toAccount, amount, TYPE_TRANSFER, dailyLimit, inGameDay));
    }

    private TxResult moveFromPersonal(int profileId, String toAccount, BigDecimal amount, String type,
                                      BigDecimal dailyLimit, long inGameDay) {
        if (!Money.isValid(amount)) {
            return TxResult.INVALID_AMOUNT;
        }
        return inTransaction(conn -> {
            String fromAccount = personalAccountNumber(conn, profileId);
            if (fromAccount == null) {
                return TxResult.NO_ACCOUNT;
            }
            return moveFunds(conn, fromAccount, toAccount, amount, type, dailyLimit, inGameDay);
        });
    }

    private TxResult moveFunds(Connection conn, String fromAccount, String toAccount, BigDecimal amount,
                               String type, BigDecimal dailyLimit, long inGameDay) throws SQLException {
        if (fromAccount.equals(toAccount)) {
            return TxResult.SAME_ACCOUNT;
        }

        // Les deux verrous sont pris dans un ordre deterministe (ORDER BY) : pas de deadlock A->B / B->A.
        BigDecimal fromBalance = null;
        BigDecimal toBalance = null;
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_number, account_balance FROM bb_bank_accounts " +
                        "WHERE account_number IN (?, ?) ORDER BY account_number FOR UPDATE")) {
            stmt.setString(1, fromAccount);
            stmt.setString(2, toAccount);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    if (fromAccount.equals(rs.getString(1))) {
                        fromBalance = rs.getBigDecimal(2);
                    } else {
                        toBalance = rs.getBigDecimal(2);
                    }
                }
            }
        }
        if (fromBalance == null) {
            return TxResult.NO_ACCOUNT;
        }
        if (toBalance == null) {
            return TxResult.TARGET_NOT_FOUND;
        }
        if (dailyLimit != null) {
            BigDecimal alreadyDone = dailyTotal(conn, type, inGameDay, "account_origin", fromAccount);
            if (alreadyDone.add(amount).compareTo(dailyLimit) > 0) {
                return TxResult.LIMIT_REACHED;
            }
        }
        if (fromBalance.compareTo(amount) < 0) {
            return TxResult.INSUFFICIENT_FUNDS;
        }
        BigDecimal newToBalance = toBalance.add(amount);
        if (newToBalance.compareTo(Money.MAX_AMOUNT) > 0) {
            return TxResult.BALANCE_OVERFLOW;
        }

        updateBalance(conn, fromAccount, fromBalance.subtract(amount));
        updateBalance(conn, toAccount, newToBalance);
        logTransaction(conn, type, amount, fromAccount, toAccount, inGameDay);
        return TxResult.OK;
    }

    // -------------------------------------------------------------------------
    // Helpers SQL
    // -------------------------------------------------------------------------

    private static String personalAccountNumber(Connection conn, int profileId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_number FROM bb_bank_accounts WHERE owner = ? AND account_type = ?")) {
            stmt.setInt(1, profileId);
            stmt.setString(2, PERSONAL);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static Balance lockPersonalAccount(Connection conn, int profileId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_number, account_balance FROM bb_bank_accounts " +
                        "WHERE owner = ? AND account_type = ? FOR UPDATE")) {
            stmt.setInt(1, profileId);
            stmt.setString(2, PERSONAL);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? new Balance(rs.getString(1), rs.getBigDecimal(2)) : null;
            }
        }
    }

    private static BigDecimal lockAccount(Connection conn, String accountNumber) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_balance FROM bb_bank_accounts WHERE account_number = ? FOR UPDATE")) {
            stmt.setString(1, accountNumber);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    private static BigDecimal dailyTotal(Connection conn, String type, long inGameDay, String column, String account)
            throws SQLException {
        // column est une constante interne ("account_origin" / "account_target"), jamais une entree utilisateur.
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT COALESCE(SUM(transaction_amount), 0) FROM bb_transactions " +
                        "WHERE transaction_type = ? AND transaction_ingame_day = ? AND " + column + " = ?")) {
            stmt.setString(1, type);
            stmt.setLong(2, inGameDay);
            stmt.setString(3, account);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        }
    }

    private static void updateBalance(Connection conn, String accountNumber, BigDecimal newBalance) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE bb_bank_accounts SET account_balance = ? WHERE account_number = ?")) {
            stmt.setBigDecimal(1, newBalance);
            stmt.setString(2, accountNumber);
            stmt.executeUpdate();
        }
    }

    private static void logTransaction(Connection conn, String type, BigDecimal amount,
                                       String origin, String target, long inGameDay) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO bb_transactions " +
                        "(transaction_type, transaction_amount, account_origin, account_target, transaction_ingame_day) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, type);
            stmt.setBigDecimal(2, amount);
            stmt.setString(3, origin);
            stmt.setString(4, target);
            stmt.setLong(5, inGameDay);
            stmt.executeUpdate();
        }
    }

    private static String uniqueIdentifier(Connection conn, String table, String column, String prefix) throws SQLException {
        // table et column sont des constantes internes.
        String sql = "SELECT 1 FROM " + table + " WHERE " + column + " = ?";
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = prefix + randomDigits(16);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, candidate);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return candidate;
                    }
                }
            }
        }
        throw new SQLException("Impossible de generer un identifiant unique pour " + table);
    }

    private static String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Transactions
    // -------------------------------------------------------------------------

    private <T> T inTransaction(TxBody<T> body) {
        SQLException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (Connection conn = source.open()) {
                conn.setAutoCommit(false);
                try {
                    T result = body.run(conn);
                    conn.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(conn);
                    throw e;
                } finally {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // la connexion est fermee juste apres
                    }
                }
            } catch (SQLException e) {
                lastError = e;
                if (!isRetryable(e)) {
                    break;
                }
            }
        }
        throw new BankException("Erreur base de donnees : " + (lastError == null ? "inconnue" : lastError.getMessage()), lastError);
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // la connexion sera fermee ; le serveur SQL annulera la transaction
        }
    }

    /** Deadlock (1213) ou echec de serialisation : SQLSTATE 40001. */
    private static boolean isRetryable(SQLException e) {
        return "40001".equals(e.getSQLState()) || e.getErrorCode() == 1213;
    }
}
