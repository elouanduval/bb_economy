package org.bb.bb_economy.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankLedgerTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();
    private static final BigDecimal NO_LIMIT = new BigDecimal("1000000.00");
    private static final long DAY = 5;

    private String url;
    private BankLedger ledger;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:bank" + DB_COUNTER.incrementAndGet() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        ledger = new BankLedger(() -> DriverManager.getConnection(url));
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE profiles (id INT AUTO_INCREMENT PRIMARY KEY)");
            stmt.execute("""
                    CREATE TABLE bb_bank_accounts (
                        account_number VARCHAR(20) NOT NULL PRIMARY KEY,
                        account_type VARCHAR(20) NOT NULL DEFAULT 'personal',
                        account_balance DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                        owner INT NULL,
                        frozen BOOLEAN NOT NULL DEFAULT FALSE,
                        CONSTRAINT chk_balance_non_negative CHECK (account_balance >= 0)
                    )""");
            stmt.execute("""
                    CREATE TABLE bb_bank_cards (
                        card_number VARCHAR(20) NOT NULL PRIMARY KEY,
                        card_pin VARCHAR(255) NOT NULL,
                        account_number VARCHAR(20) NOT NULL
                    )""");
            stmt.execute("""
                    CREATE TABLE bb_transactions (
                        transaction_id INT AUTO_INCREMENT PRIMARY KEY,
                        transaction_type VARCHAR(20) NOT NULL,
                        transaction_amount DECIMAL(10,2) NOT NULL,
                        transaction_ingame_day BIGINT NOT NULL DEFAULT 0,
                        transaction_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        account_origin VARCHAR(20) NULL,
                        account_target VARCHAR(20) NULL
                    )""");
            stmt.execute("""
                    CREATE TABLE bb_company_accounts (
                        company_id INT NOT NULL PRIMARY KEY,
                        account_number VARCHAR(20) NOT NULL
                    )""");
        }
    }

    // --- helpers -------------------------------------------------------------

    private int newProfile() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO profiles () VALUES ()", Statement.RETURN_GENERATED_KEYS)) {
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    /** Cree un profil avec un compte personnel dote de `balance`, retourne le numero de compte. */
    private int profileWithBalance(String balance) throws SQLException {
        int profile = newProfile();
        ledger.createPersonalAccount(profile, "hash", new BigDecimal(balance), DAY).orElseThrow();
        return profile;
    }

    private String accountOf(int profile) {
        return ledger.findPersonalAccountNumber(profile).orElseThrow();
    }

    private BigDecimal balanceOf(int profile) {
        return ledger.findPersonalBalance(profile).orElseThrow();
    }

    private BigDecimal totalMoney() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COALESCE(SUM(account_balance), 0) FROM bb_bank_accounts")) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private int countTransactions(String type) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM bb_transactions WHERE transaction_type = ?")) {
            stmt.setString(1, type);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    // --- creation de compte --------------------------------------------------

    @Test
    void createAccountGivesStartingBalanceAndLogsIt() throws SQLException {
        int profile = newProfile();
        var created = ledger.createPersonalAccount(profile, "hash", bd("100"), DAY);

        assertTrue(created.isPresent());
        assertTrue(created.get().accountNumber().startsWith("ACC-"));
        assertTrue(created.get().cardNumber().startsWith("CARD"));
        assertEquals(0, bd("100").compareTo(balanceOf(profile)));
        assertEquals(1, countTransactions(BankLedger.TYPE_INITIAL));
    }

    @Test
    void createAccountTwiceForSameProfileIsRefused() throws SQLException {
        int profile = newProfile();
        assertTrue(ledger.createPersonalAccount(profile, "hash", bd("0"), DAY).isPresent());
        assertTrue(ledger.createPersonalAccount(profile, "hash", bd("0"), DAY).isEmpty());
    }

    @Test
    void concurrentAccountCreationForSameProfileCreatesExactlyOne() throws Exception {
        int profile = newProfile();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> ledger.createPersonalAccount(profile, "hash", bd("0"), DAY).isPresent()));
        }
        int created = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) created++;
        }
        pool.shutdown();
        assertEquals(1, created);
    }

    @Test
    void companyAccountOwnedByProfileIsNeverUsedAsPersonalAccount() throws SQLException {
        int profile = newProfile();
        // compte d'entreprise cree AVANT le compte personnel, meme proprietaire
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO bb_bank_accounts (account_number, account_type, account_balance, owner) " +
                    "VALUES ('ACC-COMPANY', 'company', 5000.00, " + profile + ")");
        }
        assertTrue(ledger.findPersonalAccountNumber(profile).isEmpty());
        assertEquals(TxResult.NO_ACCOUNT, ledger.withdraw(profile, bd("10"), NO_LIMIT, DAY));

        ledger.createPersonalAccount(profile, "hash", bd("50"), DAY).orElseThrow();
        assertEquals(0, bd("50").compareTo(balanceOf(profile)));
        assertEquals(TxResult.OK, ledger.withdraw(profile, bd("10"), NO_LIMIT, DAY));
        assertEquals(0, bd("40").compareTo(balanceOf(profile)));
        assertEquals(0, bd("5000").compareTo(companyBalance()));
    }

    private BigDecimal companyBalance() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT account_balance FROM bb_bank_accounts WHERE account_number = 'ACC-COMPANY'")) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    // --- depot / retrait -----------------------------------------------------

    @Test
    void depositAndWithdrawMoveTheBalanceAndAreLogged() throws SQLException {
        int profile = profileWithBalance("0");

        assertEquals(TxResult.OK, ledger.deposit(profile, bd("250"), NO_LIMIT, DAY));
        assertEquals(0, bd("250").compareTo(balanceOf(profile)));
        assertEquals(TxResult.OK, ledger.withdraw(profile, bd("100"), NO_LIMIT, DAY));
        assertEquals(0, bd("150").compareTo(balanceOf(profile)));
        assertEquals(1, countTransactions(BankLedger.TYPE_DEPOSIT));
        assertEquals(1, countTransactions(BankLedger.TYPE_WITHDRAW));
    }

    @Test
    void withdrawBeyondBalanceIsRefusedAndWritesNothing() throws SQLException {
        int profile = profileWithBalance("50");

        assertEquals(TxResult.INSUFFICIENT_FUNDS, ledger.withdraw(profile, bd("50.01"), NO_LIMIT, DAY));
        assertEquals(0, bd("50").compareTo(balanceOf(profile)));
        assertEquals(0, countTransactions(BankLedger.TYPE_WITHDRAW));
    }

    @Test
    void dailyLimitCountsPreviousOperationsOfTheSameDayOnly() throws SQLException {
        int profile = profileWithBalance("1000");
        BigDecimal limit = bd("100");

        assertEquals(TxResult.OK, ledger.withdraw(profile, bd("60"), limit, DAY));
        assertEquals(TxResult.LIMIT_REACHED, ledger.withdraw(profile, bd("41"), limit, DAY));
        assertEquals(TxResult.OK, ledger.withdraw(profile, bd("40"), limit, DAY));
        assertEquals(TxResult.LIMIT_REACHED, ledger.withdraw(profile, bd("1"), limit, DAY));
        // le lendemain, le compteur repart de zero
        assertEquals(TxResult.OK, ledger.withdraw(profile, bd("100"), limit, DAY + 1));
    }

    @Test
    void depositCannotPushBalanceBeyondColumnCapacity() throws SQLException {
        int profile = profileWithBalance("99999999.00");

        assertEquals(TxResult.BALANCE_OVERFLOW, ledger.deposit(profile, bd("2"), NO_LIMIT.multiply(bd("1000")), DAY));
        assertEquals(0, bd("99999999.00").compareTo(balanceOf(profile)));
    }

    @Test
    void invalidAmountsAreRejectedBeforeTouchingTheDatabase() throws SQLException {
        int profile = profileWithBalance("100");
        int other = profileWithBalance("100");
        String otherAccount = accountOf(other);

        for (String amount : new String[]{"0", "-5", "0.001", "0.005", "100000000"}) {
            BigDecimal value = new BigDecimal(amount);
            assertEquals(TxResult.INVALID_AMOUNT, ledger.deposit(profile, value, NO_LIMIT, DAY), amount);
            assertEquals(TxResult.INVALID_AMOUNT, ledger.withdraw(profile, value, NO_LIMIT, DAY), amount);
            assertEquals(TxResult.INVALID_AMOUNT, ledger.transfer(profile, otherAccount, value, NO_LIMIT, DAY), amount);
        }
        assertEquals(0, bd("200").compareTo(totalMoney()));
    }

    // --- virements -----------------------------------------------------------

    @Test
    void transferMovesExactlyTheAmount() throws SQLException {
        int from = profileWithBalance("100");
        int to = profileWithBalance("10");

        assertEquals(TxResult.OK, ledger.transfer(from, accountOf(to), bd("33.33"), NO_LIMIT, DAY));
        assertEquals(0, bd("66.67").compareTo(balanceOf(from)));
        assertEquals(0, bd("43.33").compareTo(balanceOf(to)));
        assertEquals(1, countTransactions(BankLedger.TYPE_TRANSFER));
    }

    @Test
    void transferToSelfOrUnknownAccountIsRefused() throws SQLException {
        int from = profileWithBalance("100");

        assertEquals(TxResult.SAME_ACCOUNT, ledger.transfer(from, accountOf(from), bd("10"), NO_LIMIT, DAY));
        assertEquals(TxResult.TARGET_NOT_FOUND, ledger.transfer(from, "ACC-DOESNOTEXIST", bd("10"), NO_LIMIT, DAY));
        assertEquals(0, bd("100").compareTo(balanceOf(from)));
        assertEquals(0, countTransactions(BankLedger.TYPE_TRANSFER));
    }

    @Test
    void transferIsRefusedWhenFundsAreInsufficientOrLimitReached() throws SQLException {
        int from = profileWithBalance("100");
        int to = profileWithBalance("0");

        assertEquals(TxResult.INSUFFICIENT_FUNDS, ledger.transfer(from, accountOf(to), bd("100.01"), NO_LIMIT, DAY));
        assertEquals(TxResult.LIMIT_REACHED, ledger.transfer(from, accountOf(to), bd("50"), bd("49.99"), DAY));
        assertEquals(TxResult.OK, ledger.transfer(from, accountOf(to), bd("50"), bd("50"), DAY));
    }

    @Test
    void tpePaymentIsNotSubjectToTheTransferLimit() throws SQLException {
        int buyer = profileWithBalance("500");
        int shop = profileWithBalance("0");

        assertEquals(TxResult.OK, ledger.payTpe(buyer, accountOf(shop), bd("400"), DAY));
        assertEquals(0, bd("100").compareTo(balanceOf(buyer)));
        assertEquals(0, bd("400").compareTo(balanceOf(shop)));
        assertEquals(1, countTransactions(BankLedger.TYPE_TPE));
    }

    @Test
    void transferBetweenAccountsMovesFundsBetweenAnyTwoAccountsAndCannotOverdraw() throws SQLException {
        int from = profileWithBalance("100");
        int to = profileWithBalance("0");

        assertEquals(TxResult.OK, ledger.transferBetweenAccounts(accountOf(from), accountOf(to), bd("60"), "ADMIN", DAY));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, ledger.transferBetweenAccounts(accountOf(from), accountOf(to), bd("60"), "ADMIN", DAY));
        assertEquals(0, bd("40").compareTo(balanceOf(from)));
        assertEquals(0, bd("60").compareTo(balanceOf(to)));
    }

    // --- creation de compte entreprise ----------------------------------------

    @Test
    void createCompanyAccountLinksItToTheGivenIdWithoutACard() throws SQLException {
        var created = ledger.createCompanyAccount(1, bd("50"));

        assertTrue(created.isPresent());
        assertTrue(created.get().startsWith("ACC-"));
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT account_type, account_balance, owner FROM bb_bank_accounts WHERE account_number = ?")) {
            stmt.setString(1, created.get());
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("company", rs.getString(1));
                assertEquals(0, bd("50").compareTo(rs.getBigDecimal(2)));
                assertNull(rs.getObject(3), "un compte d'entreprise n'a pas de proprietaire");
            }
        }
    }

    @Test
    void createCompanyAccountTwiceForSameIdIsRefused() throws SQLException {
        assertTrue(ledger.createCompanyAccount(1, bd("0")).isPresent());
        assertTrue(ledger.createCompanyAccount(1, bd("0")).isEmpty());
    }

    @Test
    void companyAccountIsFoundByCompanyIdAndDifferentCompaniesGetDifferentAccounts() throws SQLException {
        String first = ledger.createCompanyAccount(1, bd("0")).orElseThrow();
        String second = ledger.createCompanyAccount(2, bd("0")).orElseThrow();

        assertNotEquals(first, second);
        assertEquals(first, ledger.findCompanyAccountNumber(1).orElseThrow());
        assertEquals(second, ledger.findCompanyAccountNumber(2).orElseThrow());
        assertTrue(ledger.findCompanyAccountNumber(3).isEmpty());
    }

    @Test
    void getOrCreateCompanyAccountOpensOnceThenAlwaysReturnsTheSameAccount() {
        String first = ledger.getOrCreateCompanyAccount(7);
        String again = ledger.getOrCreateCompanyAccount(7);

        assertTrue(first.startsWith("ACC-"));
        assertEquals(first, again);
        assertEquals(first, ledger.findCompanyAccountNumber(7).orElseThrow());
        assertNotEquals(first, ledger.getOrCreateCompanyAccount(8));
    }

    @Test
    void concurrentGetOrCreateForTheSameCompanyYieldsASingleAccount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            tasks.add(() -> ledger.getOrCreateCompanyAccount(42));
        }
        java.util.Set<String> accounts = new java.util.HashSet<>();
        for (Future<String> f : pool.invokeAll(tasks)) {
            accounts.add(f.get());
        }
        pool.shutdown();

        assertEquals(1, accounts.size(), "toutes les requetes simultanees doivent aboutir au meme compte");
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM bb_bank_accounts WHERE account_type = 'company'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "un seul compte d'entreprise doit exister en base");
        }
    }

    @Test
    void companyAccountWithoutOwnerNeverInterferesWithPersonalAccounts() throws SQLException {
        int profile = profileWithBalance("100");
        String company = ledger.getOrCreateCompanyAccount(1);

        assertEquals(accountOf(profile), ledger.findPersonalAccountNumber(profile).orElseThrow());
        assertNotEquals(company, accountOf(profile));
        // un paiement TPE du joueur vers l'entreprise credite bien le compte sans proprietaire
        assertEquals(TxResult.OK, ledger.payTpe(profile, company, bd("30"), DAY));
        assertEquals(0, bd("30").compareTo(ledger.findBalance(company).orElseThrow()));
        assertEquals(0, bd("70").compareTo(balanceOf(profile)));
    }

    // --- cartes et operations par numero de compte ----------------------------

    @Test
    void personalCardResolvesToItsAccountAndUnknownCardsToNothing() throws SQLException {
        int profile = newProfile();
        BankLedger.Created created = ledger.createPersonalAccount(profile, "hash", bd("10"), DAY).orElseThrow();

        assertEquals(created.accountNumber(), ledger.accountNumberForCard(created.cardNumber()).orElseThrow());
        assertTrue(ledger.accountNumberForCard("CARD-DOES-NOT-EXIST").isEmpty());
    }

    @Test
    void depositWithdrawAndTransferWorkDirectlyOnAnAccountNumber() throws SQLException {
        String company = ledger.createCompanyAccount(1, bd("0")).orElseThrow();
        int player = profileWithBalance("0");
        String personal = accountOf(player);

        assertEquals(TxResult.OK, ledger.deposit(company, bd("200"), NO_LIMIT, DAY));
        assertEquals(0, bd("200").compareTo(ledger.findBalance(company).orElseThrow()));

        assertEquals(TxResult.OK, ledger.withdraw(company, bd("50"), NO_LIMIT, DAY));
        assertEquals(0, bd("150").compareTo(ledger.findBalance(company).orElseThrow()));

        assertEquals(TxResult.OK, ledger.transferFromAccount(company, personal, bd("100"), NO_LIMIT, DAY));
        assertEquals(0, bd("50").compareTo(ledger.findBalance(company).orElseThrow()));
        assertEquals(0, bd("100").compareTo(balanceOf(player)));
    }

    // --- administration -------------------------------------------------------

    @Test
    void adminCreditAndDebitMoveMoneyAreLoggedAndNeverGoNegative() throws SQLException {
        int profile = profileWithBalance("100");
        String account = accountOf(profile);

        assertEquals(TxResult.OK, ledger.adminCredit(account, bd("50.50"), DAY));
        assertEquals(0, bd("150.50").compareTo(balanceOf(profile)));
        assertEquals(TxResult.OK, ledger.adminDebit(account, bd("150.50"), DAY));
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(profile)));
        assertEquals(TxResult.INSUFFICIENT_FUNDS, ledger.adminDebit(account, bd("0.01"), DAY));
        assertEquals(TxResult.INVALID_AMOUNT, ledger.adminCredit(account, bd("0"), DAY));
        assertEquals(TxResult.NO_ACCOUNT, ledger.adminCredit("ACC-DOES-NOT-EXIST", bd("1"), DAY));
        assertEquals(1, countTransactions(BankLedger.TYPE_ADMIN_CREDIT));
        assertEquals(1, countTransactions(BankLedger.TYPE_ADMIN_DEBIT));
    }

    @Test
    void adminCreditCannotOverflowTheAccount() throws SQLException {
        int profile = profileWithBalance("99999999.00");

        assertEquals(TxResult.BALANCE_OVERFLOW, ledger.adminCredit(accountOf(profile), bd("2"), DAY));
        assertEquals(0, bd("99999999.00").compareTo(balanceOf(profile)));
    }

    @Test
    void frozenAccountCannotSendMoneyButStillReceivesAndAdminCanStillDebit() throws SQLException {
        int frozen = profileWithBalance("100");
        int other = profileWithBalance("100");
        String frozenAccount = accountOf(frozen);

        assertTrue(ledger.setFrozen(frozenAccount, true));

        assertEquals(TxResult.ACCOUNT_FROZEN, ledger.withdraw(frozen, bd("10"), NO_LIMIT, DAY));
        assertEquals(TxResult.ACCOUNT_FROZEN, ledger.withdraw(frozenAccount, bd("10"), NO_LIMIT, DAY));
        assertEquals(TxResult.ACCOUNT_FROZEN, ledger.transfer(frozen, accountOf(other), bd("10"), NO_LIMIT, DAY));
        assertEquals(TxResult.ACCOUNT_FROZEN, ledger.transferFromAccount(frozenAccount, accountOf(other), bd("10"), NO_LIMIT, DAY));
        assertEquals(TxResult.ACCOUNT_FROZEN, ledger.payTpe(frozen, accountOf(other), bd("10"), DAY));
        assertEquals(0, bd("100").compareTo(balanceOf(frozen)));

        // il peut encore recevoir, et un administrateur peut encore intervenir
        assertEquals(TxResult.OK, ledger.transfer(other, frozenAccount, bd("5"), NO_LIMIT, DAY));
        assertEquals(TxResult.OK, ledger.deposit(frozenAccount, bd("5"), NO_LIMIT, DAY));
        assertEquals(TxResult.OK, ledger.adminDebit(frozenAccount, bd("10"), DAY));
        assertEquals(0, bd("100").compareTo(balanceOf(frozen)));

        assertTrue(ledger.setFrozen(frozenAccount, false));
        assertEquals(TxResult.OK, ledger.withdraw(frozen, bd("10"), NO_LIMIT, DAY));
        assertFalse(ledger.setFrozen("ACC-DOES-NOT-EXIST", true));
    }

    @Test
    void accountInfoAndCardNumbersDescribeTheAccount() throws SQLException {
        int profile = newProfile();
        BankLedger.Created created = ledger.createPersonalAccount(profile, "hash", bd("12.34"), DAY).orElseThrow();

        BankLedger.AccountInfo info = ledger.accountInfo(created.accountNumber()).orElseThrow();
        assertEquals("personal", info.type());
        assertEquals(0, bd("12.34").compareTo(info.balance()));
        assertFalse(info.frozen());
        assertEquals(List.of(created.cardNumber()), ledger.cardNumbersOf(created.accountNumber()));
        assertTrue(ledger.accountInfo("ACC-DOES-NOT-EXIST").isEmpty());
    }

    @Test
    void recentTransactionsListsBothDirectionsNewestFirstAndHonoursTheLimit() throws SQLException {
        int a = profileWithBalance("100");
        int b = profileWithBalance("0");
        String accountA = accountOf(a);

        ledger.transfer(a, accountOf(b), bd("10"), NO_LIMIT, DAY);   // sortant pour A
        ledger.transfer(a, accountOf(b), bd("20"), NO_LIMIT, DAY);
        ledger.deposit(accountA, bd("5"), NO_LIMIT, DAY);            // entrant pour A

        List<BankLedger.TxRecord> all = ledger.recentTransactions(accountA, 50);
        // INITIAL (creation avec solde) + 2 virements + 1 depot
        assertEquals(4, all.size());
        assertEquals(BankLedger.TYPE_DEPOSIT, all.get(0).type());
        assertEquals(0, bd("5").compareTo(all.get(0).amount()));
        assertEquals(0, bd("20").compareTo(all.get(1).amount()));
        assertTrue(all.get(0).id() > all.get(1).id());
        assertEquals(2, ledger.recentTransactions(accountA, 2).size());
        // l'historique d'un compte ne contient pas les operations des autres comptes
        assertTrue(ledger.recentTransactions("ACC-DOES-NOT-EXIST", 10).isEmpty());
    }

    @Test
    void renumberedCardGetsANewNumberAndTheOldOneStopsResolving() throws SQLException {
        int profile = newProfile();
        BankLedger.Created created = ledger.createPersonalAccount(profile, "hash", bd("0"), DAY).orElseThrow();

        String newNumber = ledger.renumberCard(created.cardNumber()).orElseThrow();

        assertNotEquals(created.cardNumber(), newNumber);
        assertTrue(ledger.accountNumberForCard(created.cardNumber()).isEmpty());
        assertEquals(created.accountNumber(), ledger.accountNumberForCard(newNumber).orElseThrow());
        assertTrue(ledger.renumberCard("CARD-DOES-NOT-EXIST").isEmpty());
    }

    // --- boutiques automatiques -----------------------------------------------

    @Test
    void shopCashTradesMoveTheCompanyAccountAndAreLoggedWithTheirOwnTypes() throws SQLException {
        String company = ledger.createCompanyAccount(1, bd("100")).orElseThrow();

        // le joueur remet 30 en billets a la boutique : le compte de l'entreprise est credite
        assertEquals(TxResult.OK, ledger.creditCash(company, bd("30"), BankLedger.TYPE_SHOP_BUY_CASH, DAY));
        assertEquals(0, bd("130").compareTo(ledger.findBalance(company).orElseThrow()));
        // la boutique achete des objets en liquide : le compte est debite
        assertEquals(TxResult.OK, ledger.debitCash(company, bd("130"), BankLedger.TYPE_SHOP_SELL_CASH, DAY));
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.findBalance(company).orElseThrow()));

        assertEquals(1, countTransactions(BankLedger.TYPE_SHOP_BUY_CASH));
        assertEquals(1, countTransactions(BankLedger.TYPE_SHOP_SELL_CASH));
    }

    @Test
    void shopCannotPayMoreThanTheCompanyHasNorFromAFrozenAccount() throws SQLException {
        String company = ledger.createCompanyAccount(1, bd("10")).orElseThrow();

        assertEquals(TxResult.INSUFFICIENT_FUNDS, ledger.debitCash(company, bd("10.01"), BankLedger.TYPE_SHOP_SELL_CASH, DAY));
        assertEquals(0, bd("10").compareTo(ledger.findBalance(company).orElseThrow()));

        ledger.setFrozen(company, true);
        assertEquals(TxResult.ACCOUNT_FROZEN, ledger.debitCash(company, bd("1"), BankLedger.TYPE_SHOP_SELL_CASH, DAY));
        // un compte gele peut encore encaisser
        assertEquals(TxResult.OK, ledger.creditCash(company, bd("1"), BankLedger.TYPE_SHOP_BUY_CASH, DAY));
    }

    @Test
    void shopCardTradesAreOrdinaryTransfersBetweenThePlayerAndTheCompanyAccount() throws SQLException {
        String company = ledger.createCompanyAccount(1, bd("50")).orElseThrow();
        int player = profileWithBalance("20");

        // le joueur vend a la boutique : l'entreprise paie sur le compte du joueur
        assertEquals(TxResult.OK, ledger.transferBetweenAccounts(company, accountOf(player), bd("30"), BankLedger.TYPE_SHOP_SELL, DAY));
        assertEquals(0, bd("50").compareTo(balanceOf(player)));
        // le joueur achete : il paie l'entreprise
        assertEquals(TxResult.OK, ledger.transferBetweenAccounts(accountOf(player), company, bd("50"), BankLedger.TYPE_SHOP_BUY, DAY));
        assertEquals(0, bd("70").compareTo(ledger.findBalance(company).orElseThrow()));
        // l'entreprise ne peut pas payer plus que son solde
        assertEquals(TxResult.INSUFFICIENT_FUNDS,
                ledger.transferBetweenAccounts(company, accountOf(player), bd("70.01"), BankLedger.TYPE_SHOP_SELL, DAY));
    }

    // --- concurrence ---------------------------------------------------------

    @Test
    void concurrentWithdrawalsCannotExceedTheDailyLimit() throws Exception {
        int profile = profileWithBalance("1000");
        BigDecimal limit = bd("100");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<TxResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            tasks.add(() -> ledger.withdraw(profile, bd("10"), limit, DAY));
        }
        int ok = 0;
        for (Future<TxResult> f : pool.invokeAll(tasks)) {
            if (f.get() == TxResult.OK) ok++;
        }
        pool.shutdown();

        assertEquals(10, ok, "exactement 10 retraits de 10 pour un plafond de 100");
        assertEquals(0, bd("900").compareTo(balanceOf(profile)));
    }

    @Test
    void concurrentOppositeTransfersNeitherDeadlockNorLoseMoney() throws Exception {
        int a = profileWithBalance("1000");
        int b = profileWithBalance("1000");
        String accountA = accountOf(a);
        String accountB = accountOf(b);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<TxResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> ledger.transfer(a, accountB, bd("1"), NO_LIMIT, DAY));
            tasks.add(() -> ledger.transfer(b, accountA, bd("1"), NO_LIMIT, DAY));
        }
        for (Future<TxResult> f : pool.invokeAll(tasks)) {
            assertEquals(TxResult.OK, f.get());
        }
        pool.shutdown();

        assertEquals(0, bd("2000").compareTo(totalMoney()));
        assertEquals(0, bd("1000").compareTo(balanceOf(a)));
        assertEquals(0, bd("1000").compareTo(balanceOf(b)));
    }

    @Test
    void concurrentWithdrawalsNeverMakeTheBalanceNegative() throws Exception {
        int profile = profileWithBalance("100");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Callable<TxResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> ledger.withdraw(profile, bd("10"), NO_LIMIT, DAY));
        }
        int ok = 0;
        for (Future<TxResult> f : pool.invokeAll(tasks)) {
            TxResult result = f.get();
            assertFalse(result == TxResult.LIMIT_REACHED);
            if (result == TxResult.OK) ok++;
        }
        pool.shutdown();

        assertEquals(10, ok);
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(profile)));
    }
}
