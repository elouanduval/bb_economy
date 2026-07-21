package org.bb.bb_economy.database;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.bb.bb_economy.Config;
import org.bb.bb_economy.init.ModItems;
import org.bb.bb_economy.item.CardItem;
import org.jline.utils.Log;

import java.io.Console;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class BankManager {

    private static final SecureRandom RANDOM = new SecureRandom();

    // -------------------------------------------------------------------------
    // Helper : récupère le profile_id actif du joueur depuis mc_accounts
    // Retourne -1 si pas de profil actif
    // -------------------------------------------------------------------------
    public static int getActiveProfileId(Player player) {
        String uuid = player.getUUID().toString();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT active_profile FROM mc_accounts WHERE uuid = ?")) {
            stmt.setString(1, uuid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next() && rs.getObject("active_profile") != null) {
                return rs.getInt("active_profile");
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur getActiveProfileId : " + e.getMessage(), e);
        }
    }

    public static boolean hasActiveProfile(Player player) {
        return getActiveProfileId(player) != -1;
    }

    // -------------------------------------------------------------------------
    // Compte bancaire
    // -------------------------------------------------------------------------

    public static boolean hasAccount(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT account_number FROM bb_bank_accounts WHERE owner = ?")) {
            stmt.setInt(1, profileId);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur hasAccount : " + e.getMessage(), e);
        }
    }

    public static String getAccountNumber(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) {
            throw new RuntimeException("[BB Economy] Aucun profil actif pour " + player.getName().getString());
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT account_number FROM bb_bank_accounts WHERE owner = ?")) {
            stmt.setInt(1, profileId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("account_number");
            }
            throw new RuntimeException("[BB Economy] Compte introuvable pour profil " + profileId);
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur getAccountNumber : " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Entreprises
    // -------------------------------------------------------------------------

    public static boolean companyExists(String companyId) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT company_id FROM bb_companies WHERE company_id = ?")) {
            stmt.setString(1, normalizeCompanyId(companyId));
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur companyExists : " + e.getMessage(), e);
        }
    }

    public static String getCompanyAccountNumber(String companyId) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT account_number FROM bb_companies WHERE company_id = ?")) {
            stmt.setString(1, normalizeCompanyId(companyId));
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("account_number");
            }
            throw new RuntimeException("[BB Economy] Entreprise introuvable : " + companyId);
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur getCompanyAccountNumber : " + e.getMessage(), e);
        }
    }

    public static boolean playerWorksForCompany(Player player, String companyId) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT job_id FROM bb_jobs WHERE owner = ? AND company_id = ? LIMIT 1")) {
            stmt.setInt(1, profileId);
            stmt.setString(2, normalizeCompanyId(companyId));
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur playerWorksForCompany : " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Cartes bancaires
    // -------------------------------------------------------------------------

    public static boolean hasDefaultCardPin(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT bc.card_number FROM bb_bank_cards bc " +
                             "JOIN bb_bank_accounts ba ON bc.account_number = ba.account_number " +
                             "WHERE ba.owner = ? AND (bc.card_pin = ? OR bc.card_pin = ?)")) {
            stmt.setInt(1, profileId);
            stmt.setString(2, hashPin(getDefaultCardPin()));
            stmt.setString(3, legacyHashPin(getDefaultCardPin()));
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur hasDefaultCardPin : " + e.getMessage(), e);
        }
    }

    public static String getCardNumber(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) {
            throw new RuntimeException("[BB Economy] Aucun profil actif pour " + player.getName().getString());
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT bc.card_number FROM bb_bank_cards bc " +
                             "JOIN bb_bank_accounts ba ON bc.account_number = ba.account_number " +
                             "WHERE ba.owner = ? LIMIT 1")) {
            stmt.setInt(1, profileId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("card_number");
            }
            throw new RuntimeException("[BB Economy] Carte introuvable pour profil " + profileId);
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur getCardNumber : " + e.getMessage(), e);
        }
    }

    public static boolean cardExists(String cardNumber) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT card_number FROM bb_bank_cards WHERE card_number = ?")) {
            stmt.setString(1, cardNumber);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur cardExists : " + e.getMessage(), e);
        }
    }

    public static boolean playerOwnsCard(Player player, String cardNumber) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT bc.card_number FROM bb_bank_cards bc " +
                             "JOIN bb_bank_accounts ba ON bc.account_number = ba.account_number " +
                             "WHERE ba.owner = ? AND bc.card_number = ?")) {
            stmt.setInt(1, profileId);
            stmt.setString(2, cardNumber);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur playerOwnsCard : " + e.getMessage(), e);
        }
    }

    public static boolean hasOwnedCardInInventory(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() != ModItems.CARD.get()) continue;
            String cardNumber = CardItem.getCardNumber(stack);
            if (!cardNumber.isBlank() && playerOwnsCard(player, cardNumber)) return true;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.getItem() != ModItems.CARD.get()) continue;
            String cardNumber = CardItem.getCardNumber(stack);
            if (!cardNumber.isBlank() && playerOwnsCard(player, cardNumber)) return true;
        }
        return false;
    }

    public static boolean hasOwnedCardInHand(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        if (mainHand.getItem() == ModItems.CARD.get()) {
            String cardNumber = CardItem.getCardNumber(mainHand);
            if (!cardNumber.isBlank() && playerOwnsCard(player, cardNumber)) return true;
        }
        ItemStack offHand = player.getOffhandItem();
        if (offHand.getItem() == ModItems.CARD.get()) {
            String cardNumber = CardItem.getCardNumber(offHand);
            if (!cardNumber.isBlank() && playerOwnsCard(player, cardNumber)) return true;
        }
        return false;
    }

    public static boolean checkPin(Player player, int pin) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT bc.card_number FROM bb_bank_cards bc " +
                             "JOIN bb_bank_accounts ba ON bc.account_number = ba.account_number " +
                             "WHERE ba.owner = ? AND (bc.card_pin = ? OR bc.card_pin = ?)")) {
            stmt.setInt(1, profileId);
            stmt.setString(2, hashPin(pin));
            stmt.setString(3, legacyHashPin(pin));
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur checkPin : " + e.getMessage(), e);
        }
    }

    public static boolean updateCardPin(Player player, String pin) {
        if (!isValidPin(pin)) return false;
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement getAccount = conn.prepareStatement(
                     "SELECT account_number FROM bb_bank_accounts WHERE owner = ?");
             PreparedStatement updateCard = conn.prepareStatement(
                     "UPDATE bb_bank_cards SET card_pin = ? WHERE account_number = ?")) {
            getAccount.setInt(1, profileId);
            ResultSet rs = getAccount.executeQuery();
            if (!rs.next()) return false;
            updateCard.setString(1, hashPin(pin));
            updateCard.setString(2, rs.getString("account_number"));
            return updateCard.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur updateCardPin : " + e.getMessage(), e);
        }
    }

    public static boolean resetCardPinToDefault(Player player) {
        return updateCardPin(player, String.format("%04d", getDefaultCardPin()));
    }

    public static void addCard(Player player, String cardNumber, int pin) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) {
            throw new RuntimeException("[BB Economy] Aucun profil actif pour " + player.getName().getString());
        }
        try (Connection conn = DatabaseManager.getConnection()) {
            PreparedStatement getAccount = conn.prepareStatement(
                    "SELECT account_number FROM bb_bank_accounts WHERE owner = ?");
            getAccount.setInt(1, profileId);
            ResultSet rs = getAccount.executeQuery();
            if (!rs.next()) throw new RuntimeException("Compte introuvable pour profil " + profileId);

            PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO bb_bank_cards (card_number, card_pin, account_number) VALUES (?, ?, ?)");
            insert.setString(1, cardNumber);
            insert.setString(2, hashPin(pin));
            insert.setString(3, rs.getString("account_number"));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur addCard : " + e.getMessage(), e);
        }
    }

    public static String createBankAccountAndCard(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) {
            throw new RuntimeException("[BB Economy] Aucun profil actif pour " + player.getName().getString());
        }

        final String accountNumber;
        final String cardNumber;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String existingAccount = findAccountNumberByOwner(conn, profileId);
                if (existingAccount != null) {
                    conn.rollback();
                    throw new IllegalStateException("[BB Economy] Un compte bancaire existe deja pour le profil " + profileId);
                }

                accountNumber = generateUniqueAccountNumber(conn);
                cardNumber = generateUniqueCardNumber(conn);

                try (PreparedStatement insertAccount = conn.prepareStatement(
                        "INSERT INTO bb_bank_accounts (account_number, account_type, account_balance, owner) VALUES (?, ?, ?, ?)");
                     PreparedStatement insertCard = conn.prepareStatement(
                             "INSERT INTO bb_bank_cards (card_number, card_pin, account_number) VALUES (?, ?, ?)")) {
                    insertAccount.setString(1, accountNumber);
                    insertAccount.setString(2, "personal");
                    insertAccount.setBigDecimal(3, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                    insertAccount.setInt(4, profileId);
                    insertAccount.executeUpdate();

                    insertCard.setString(1, cardNumber);
                    insertCard.setString(2, hashPin(getDefaultCardPin()));
                    insertCard.setString(3, accountNumber);
                    insertCard.executeUpdate();
                }

                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur createBankAccountAndCard : " + e.getMessage(), e);
        }

        giveCardItemByNumber(player, cardNumber);
        giveWalletItem(player);
        return cardNumber;
    }

    public static String reissueCard(Player player) {
        String cardNumber = getCardNumber(player);
        if (!resetCardPinToDefault(player)) {
            throw new RuntimeException("[BB Economy] Impossible de reinitialiser le PIN de la carte.");
        }
        if (!giveCardItemByNumber(player, cardNumber)) {
            throw new RuntimeException("[BB Economy] Impossible de redonner la carte bancaire.");
        }
        return cardNumber;
    }

    // -------------------------------------------------------------------------
    // Solde & transactions
    // -------------------------------------------------------------------------

    public static BigDecimal getBalance(Player player) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) {
            throw new RuntimeException("[BB Economy] Aucun profil actif pour " + player.getName().getString());
        }
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT account_balance FROM bb_bank_accounts WHERE owner = ?")) {
            stmt.setInt(1, profileId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) return rs.getBigDecimal("account_balance");
            throw new RuntimeException("[BB Economy] Compte introuvable pour profil " + profileId);
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur getBalance : " + e.getMessage(), e);
        }
    }

    public static String validateDeposit(Player player, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return "Montant invalide.";
        if (getRemainingDailyDepositLimit(player).compareTo(amount) < 0) return "Limite de depot journaliere atteinte.";
        return null;
    }

    public static String validateWithdraw(Player player, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return "Montant invalide.";
        if (getRemainingDailyWithdrawLimit(player).compareTo(amount) < 0) return "Limite de retrait journaliere atteinte.";
        return null;
    }

    public static String validateTransfer(Player player, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return "Montant invalide.";
        if (getRemainingDailyTransferLimit(player).compareTo(amount) < 0) return "Limite de virement journaliere atteinte.";
        return null;
    }

    public static boolean withdraw(Player player, BigDecimal amount) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        long inGameDay = getCurrentInGameDay(player);

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PreparedStatement check = conn.prepareStatement(
                        "SELECT account_balance, account_number FROM bb_bank_accounts WHERE owner = ? FOR UPDATE");
                check.setInt(1, profileId);
                ResultSet rs = check.executeQuery();
                if (!rs.next() || rs.getBigDecimal("account_balance").compareTo(amount) < 0) {
                    conn.rollback();
                    return false;
                }
                String accountNumber = rs.getString("account_number");

                PreparedStatement update = conn.prepareStatement(
                        "UPDATE bb_bank_accounts SET account_balance = account_balance - ? WHERE owner = ?");
                update.setBigDecimal(1, amount);
                update.setInt(2, profileId);
                update.executeUpdate();

                logTransaction(conn, "ATM_WITHDRAW", amount, accountNumber, null, inGameDay);
                conn.commit();

                int units = amount.intValue();
                if (units > 0) { addMoneyStacks(player, units); syncInventory(player); }
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur retrait : " + e.getMessage(), e);
        }
    }

    public static boolean deposit(Player player, BigDecimal amount) {
        int profileId = getActiveProfileId(player);
        if (profileId == -1) return false;
        long inGameDay = getCurrentInGameDay(player);

        int totalBills = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.getItem() == ModItems.MONEY.get()) totalBills += stack.getCount();
        }
        if (totalBills < amount.intValue()) return false;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PreparedStatement check = conn.prepareStatement(
                        "SELECT account_number FROM bb_bank_accounts WHERE owner = ?");
                check.setInt(1, profileId);
                ResultSet rs = check.executeQuery();
                if (!rs.next()) { conn.rollback(); return false; }
                String accountNumber = rs.getString("account_number");

                PreparedStatement update = conn.prepareStatement(
                        "UPDATE bb_bank_accounts SET account_balance = account_balance + ? WHERE owner = ?");
                update.setBigDecimal(1, amount);
                update.setInt(2, profileId);
                update.executeUpdate();

                logTransaction(conn, "ATM_DEPOSIT", amount, null, accountNumber, inGameDay);
                conn.commit();

                int remaining = amount.intValue();
                for (ItemStack stack : player.getInventory().items) {
                    if (remaining <= 0) break;
                    if (stack.getItem() == ModItems.MONEY.get()) {
                        int taken = Math.min(stack.getCount(), remaining);
                        stack.shrink(taken);
                        remaining -= taken;
                    }
                }
                syncInventory(player);
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur depot : " + e.getMessage(), e);
        }
    }

    public static boolean transfer(Player fromPlayer, String toAccountNumber, BigDecimal amount) {
        int profileId = getActiveProfileId(fromPlayer);
        if (profileId == -1) return false;
        long inGameDay = getCurrentInGameDay(fromPlayer);

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PreparedStatement checkFrom = conn.prepareStatement(
                        "SELECT account_balance, account_number FROM bb_bank_accounts WHERE owner = ? FOR UPDATE");
                checkFrom.setInt(1, profileId);
                ResultSet rsFrom = checkFrom.executeQuery();
                if (!rsFrom.next() || rsFrom.getBigDecimal("account_balance").compareTo(amount) < 0) {
                    conn.rollback();
                    return false;
                }
                String fromAccount = rsFrom.getString("account_number");

                PreparedStatement checkTo = conn.prepareStatement(
                        "SELECT account_number FROM bb_bank_accounts WHERE account_number = ? FOR UPDATE");
                checkTo.setString(1, toAccountNumber);
                if (!checkTo.executeQuery().next()) { conn.rollback(); return false; }

                PreparedStatement debit = conn.prepareStatement(
                        "UPDATE bb_bank_accounts SET account_balance = account_balance - ? WHERE owner = ?");
                debit.setBigDecimal(1, amount);
                debit.setInt(2, profileId);
                debit.executeUpdate();

                PreparedStatement credit = conn.prepareStatement(
                        "UPDATE bb_bank_accounts SET account_balance = account_balance + ? WHERE account_number = ?");
                credit.setBigDecimal(1, amount);
                credit.setString(2, toAccountNumber);
                credit.executeUpdate();

                logTransaction(conn, "TRANSFER", amount, fromAccount, toAccountNumber, inGameDay);
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur virement : " + e.getMessage(), e);
        }
    }

    public static boolean processTpePayment(Player buyer, String companyAccount, BigDecimal amount) {
        int profileId = getActiveProfileId(buyer);
        if (profileId == -1) return false;
        long inGameDay = getCurrentInGameDay(buyer);

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PreparedStatement checkBuyer = conn.prepareStatement(
                        "SELECT account_balance, account_number FROM bb_bank_accounts WHERE owner = ? FOR UPDATE");
                checkBuyer.setInt(1, profileId);
                ResultSet buyerResult = checkBuyer.executeQuery();
                if (!buyerResult.next() || buyerResult.getBigDecimal("account_balance").compareTo(amount) < 0) {
                    conn.rollback();
                    return false;
                }
                String buyerAccount = buyerResult.getString("account_number");

                PreparedStatement checkCompany = conn.prepareStatement(
                        "SELECT account_number FROM bb_bank_accounts WHERE account_number = ? FOR UPDATE");
                checkCompany.setString(1, companyAccount);
                if (!checkCompany.executeQuery().next()) { conn.rollback(); return false; }

                PreparedStatement debitBuyer = conn.prepareStatement(
                        "UPDATE bb_bank_accounts SET account_balance = account_balance - ? WHERE account_number = ?");
                debitBuyer.setBigDecimal(1, amount);
                debitBuyer.setString(2, buyerAccount);
                debitBuyer.executeUpdate();

                PreparedStatement creditCompany = conn.prepareStatement(
                        "UPDATE bb_bank_accounts SET account_balance = account_balance + ? WHERE account_number = ?");
                creditCompany.setBigDecimal(1, amount);
                creditCompany.setString(2, companyAccount);
                creditCompany.executeUpdate();

                logTransaction(conn, "TPE", amount, buyerAccount, companyAccount, inGameDay);
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur processTpePayment : " + e.getMessage(), e);
        }
    }

    public static void processSalaries(long currentDayTime) {
        int tick = (int) (currentDayTime % 24000);
        long inGameDay = currentDayTime / 24000L;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT j.job_id, j.job_salary, j.owner AS profile_id, j.company_id, " +
                             "       c.account_number AS company_account " +
                             "FROM bb_jobs j " +
                             "JOIN bb_companies c ON j.company_id = c.company_id " +
                             "WHERE j.job_salary_time = ?")) {

            stmt.setInt(1, tick);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int ownerProfileId = rs.getInt("profile_id");
                BigDecimal salary = rs.getBigDecimal("job_salary");
                String companyAccount = rs.getString("company_account");
                String jobId = rs.getString("job_id");

                PreparedStatement getAccount = conn.prepareStatement(
                        "SELECT account_number FROM bb_bank_accounts WHERE owner = ?");
                getAccount.setInt(1, ownerProfileId);
                ResultSet rsAcc = getAccount.executeQuery();
                if (!rsAcc.next()) continue;
                String playerAccount = rsAcc.getString("account_number");

                BigDecimal companyBalance = getCompanyBalance(conn, companyAccount);
                if (companyBalance.compareTo(salary) < 0) {
                    System.out.println("[BB Economy] Salaire non verse pour job " + jobId +
                            " : solde entreprise insuffisant.");
                    continue;
                }

                conn.setAutoCommit(false);
                try {
                    PreparedStatement debit = conn.prepareStatement(
                            "UPDATE bb_bank_accounts SET account_balance = account_balance - ? WHERE account_number = ?");
                    debit.setBigDecimal(1, salary);
                    debit.setString(2, companyAccount);
                    debit.executeUpdate();

                    PreparedStatement credit = conn.prepareStatement(
                            "UPDATE bb_bank_accounts SET account_balance = account_balance + ? WHERE account_number = ?");
                    credit.setBigDecimal(1, salary);
                    credit.setString(2, playerAccount);
                    credit.executeUpdate();

                    logTransaction(conn, "SALARY", salary, companyAccount, playerAccount, inGameDay);
                    conn.commit();
                    System.out.println("[BB Economy] Salaire verse : " + salary +
                            " de " + companyAccount + " vers " + playerAccount);
                } catch (SQLException e) {
                    conn.rollback();
                    System.err.println("[BB Economy] Erreur versement salaire job " + jobId + " : " + e.getMessage());
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            System.err.println("[BB Economy] Erreur processSalaries : " + e.getMessage());
        }
    }

    private static BigDecimal getCompanyBalance(Connection conn, String accountNumber) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_balance FROM bb_bank_accounts WHERE account_number = ?");
        stmt.setString(1, accountNumber);
        ResultSet rs = stmt.executeQuery();
        return rs.next() ? rs.getBigDecimal("account_balance") : BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------
    // Items
    // -------------------------------------------------------------------------

    public static ItemStack createCardItem(String cardNumber) {
        ItemStack stack = new ItemStack(ModItems.CARD.get());
        stack.getOrCreateTag().putString(CardItem.TAG_CARD_NUMBER, cardNumber);
        return stack;
    }

    public static boolean giveCardItem(Player player) {
        return giveCardItemByNumber(player, getCardNumber(player));
    }

    public static boolean giveCardItemByNumber(Player player, String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) return false;
        if (!playerOwnsCard(player, cardNumber)) return false;
        ItemStack cardStack = createCardItem(cardNumber);
        boolean added = player.getInventory().add(cardStack);
        if (!added) player.drop(cardStack, false);
        markCardItemGiven(cardNumber, player.getUUID().toString());
        return true;
    }

    public static boolean giveWalletItem(Player player) {
        ItemStack walletStack = new ItemStack(ModItems.WALLET.get());
        boolean added = player.getInventory().add(walletStack);
        if (!added) player.drop(walletStack, false);
        return true;
    }

    public static void markCardItemGiven(String cardNumber, String receiverUuid) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE bb_bank_cards " +
                             "SET card_last_item_given_at = CURRENT_TIMESTAMP, card_last_item_receiver = ? " +
                             "WHERE card_number = ?")) {
            stmt.setString(1, receiverUuid);
            stmt.setString(2, cardNumber);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur markCardItemGiven : " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // PIN
    // -------------------------------------------------------------------------

    public static int getDefaultCardPin() {
        return Config.DEFAULT_CARD_PIN;
    }

    public static String hashPin(int pin) {
        return hashPin(normalizePin(pin));
    }

    public static String hashPin(String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(pin.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 non disponible", e);
        }
    }

    private static String legacyHashPin(int pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(String.valueOf(pin).getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 non disponible", e);
        }
    }

    private static String normalizePin(int pin) {
        if (pin < 0 || pin > 9999) throw new IllegalArgumentException("Le PIN doit contenir 4 chiffres");
        return String.format("%04d", pin);
    }

    public static boolean isValidPin(String pin) {
        return pin != null && pin.matches("\\d{4}");
    }

    // -------------------------------------------------------------------------
    // Utilitaires
    // -------------------------------------------------------------------------

    public static String normalizeCompanyId(String companyId) {
        if (companyId == null) throw new IllegalArgumentException("companyId ne peut pas etre null");
        String value = companyId.trim().toUpperCase();
        if (value.isBlank()) throw new IllegalArgumentException("companyId ne peut pas etre vide");
        return value;
    }

    private static void syncInventory(Player player) {
        player.getInventory().setChanged();
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.containerMenu.broadcastChanges();
            serverPlayer.inventoryMenu.broadcastChanges();
        }
    }

    private static BigDecimal getRemainingDailyDepositLimit(Player player) {
        return getConfiguredDailyLimit(Config.DAILY_DEPOSIT_LIMIT)
                .subtract(getDailyTransactionTotal(getAccountNumber(player), "ATM_DEPOSIT", false, getCurrentInGameDay(player)))
                .max(BigDecimal.ZERO);
    }

    private static BigDecimal getRemainingDailyWithdrawLimit(Player player) {
        return getConfiguredDailyLimit(Config.DAILY_WITHDRAW_LIMIT)
                .subtract(getDailyTransactionTotal(getAccountNumber(player), "ATM_WITHDRAW", true, getCurrentInGameDay(player)))
                .max(BigDecimal.ZERO);
    }

    private static BigDecimal getRemainingDailyTransferLimit(Player player) {
        return getConfiguredDailyLimit(Config.DAILY_TRANSFER_LIMIT)
                .subtract(getDailyTransactionTotal(getAccountNumber(player), "TRANSFER", true, getCurrentInGameDay(player)))
                .max(BigDecimal.ZERO);
    }

    private static BigDecimal getDailyTransactionTotal(String accountNumber, String transactionType,
                                                       boolean useOrigin, long inGameDay) {
        String column = useOrigin ? "account_origin" : "account_target";
        String sql = "SELECT COALESCE(SUM(transaction_amount), 0) AS total " +
                "FROM bb_transactions WHERE transaction_type = ? AND transaction_ingame_day = ? AND " + column + " = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, transactionType);
            stmt.setLong(2, inGameDay);
            stmt.setString(3, accountNumber);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getBigDecimal("total") : BigDecimal.ZERO;
        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur lecture limite journaliere : " + e.getMessage(), e);
        }
    }

    private static BigDecimal getConfiguredDailyLimit(double configuredValue) {
        return configAmount(configuredValue);
    }

    private static BigDecimal configAmount(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static long getCurrentInGameDay(Player player) {
        return player.level().getDayTime() / 24000L;
    }

    private static void addMoneyStacks(Player player, int amount) {
        int remaining = amount;
        int maxStackSize = ModItems.MONEY.get().getDefaultInstance().getMaxStackSize();
        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStackSize);
            ItemStack moneyStack = new ItemStack(ModItems.MONEY.get(), stackSize);
            boolean added = player.getInventory().add(moneyStack);
            if (!added) player.drop(moneyStack, false);
            remaining -= stackSize;
        }
    }

    private static void logTransaction(Connection conn, String type, BigDecimal amount,
                                       String origin, String target, long inGameDay) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO bb_transactions " +
                        "(transaction_type, transaction_amount, account_origin, account_target, transaction_ingame_day) " +
                        "VALUES (?, ?, ?, ?, ?)");
        stmt.setString(1, type);
        stmt.setBigDecimal(2, amount);
        stmt.setString(3, origin);
        stmt.setString(4, target);
        stmt.setLong(5, inGameDay);
        stmt.executeUpdate();
    }

    private static String findAccountNumberByOwner(Connection conn, int profileId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT account_number FROM bb_bank_accounts WHERE owner = ? LIMIT 1")) {
            stmt.setInt(1, profileId);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? rs.getString("account_number") : null;
        }
    }

    private static String generateUniqueAccountNumber(Connection conn) throws SQLException {
        return generateUniqueIdentifier(conn, "bb_bank_accounts", "account_number", "ACC-");
    }

    private static String generateUniqueCardNumber(Connection conn) throws SQLException {
        return generateUniqueIdentifier(conn, "bb_bank_cards", "card_number", "CARD");
    }

    private static String generateUniqueIdentifier(Connection conn, String tableName, String columnName, String prefix)
            throws SQLException {
        String sql = "SELECT 1 FROM " + tableName + " WHERE " + columnName + " = ?";
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = prefix + randomDigits(16);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, candidate);
                if (!stmt.executeQuery().next()) {
                    return candidate;
                }
            }
        }
        throw new SQLException("Impossible de generer un identifiant unique pour " + tableName);
    }

    private static String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}
