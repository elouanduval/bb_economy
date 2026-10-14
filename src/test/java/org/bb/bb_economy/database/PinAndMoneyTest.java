package org.bb.bb_economy.database;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinAndMoneyTest {

    // --- Money ---------------------------------------------------------------

    @Test
    void parsePositiveAcceptsPlainAmountsWithUpToTwoDecimals() {
        assertEquals(new BigDecimal("12.00"), Money.parsePositive("12").orElseThrow());
        assertEquals(new BigDecimal("12.50"), Money.parsePositive(" 12,5 ").orElseThrow());
        assertEquals(new BigDecimal("0.01"), Money.parsePositive("0.01").orElseThrow());
        assertEquals(Money.MAX_AMOUNT, Money.parsePositive("99999999.99").orElseThrow());
    }

    @Test
    void parsePositiveRejectsEverythingElse() {
        for (String bad : new String[]{null, "", " ", "0", "0.00", "-1", "+1", "1e5", "1E999999999", "0x10", "1.234",
                "100000000", "99999999.999", "1 000", "NaN", "١٢٣", ".5", "5."}) {
            assertTrue(Money.parsePositive(bad).isEmpty(), "doit etre refuse : " + bad);
        }
    }

    @Test
    void wholeAmountsAndBillCounts() {
        assertTrue(Money.isWhole(new BigDecimal("100.00")));
        assertTrue(Money.isWhole(new BigDecimal("1E+2")));
        assertFalse(Money.isWhole(new BigDecimal("100.50")));
        assertEquals(100, Money.toBills(new BigDecimal("100.00")));
        assertEquals(99999999, Money.toBills(Money.MAX_AMOUNT.setScale(0, java.math.RoundingMode.DOWN)));
    }

    // --- PinHasher -----------------------------------------------------------

    @Test
    void hashVerifiesOnlyTheRightPinAndIsSalted() {
        String hash = PinHasher.hash("1234");
        assertTrue(PinHasher.verify("1234", hash));
        assertFalse(PinHasher.verify("1235", hash));
        assertFalse(PinHasher.verify("0000", hash));
        assertNotEquals(hash, PinHasher.hash("1234"), "deux hash du meme PIN doivent differer (sel)");
        assertTrue(hash.length() <= 255);
        assertFalse(PinHasher.needsUpgrade(hash));
    }

    @Test
    void legacySha256HashesStillVerifyAndAreFlaggedForUpgrade() throws Exception {
        String legacyPadded = sha256Hex("0042");
        String legacyPlain = sha256Hex("42"); // l'ancienne version ne remplissait pas de zeros
        String legacyZero = sha256Hex("0");

        assertTrue(PinHasher.verify("0042", legacyPadded));
        assertTrue(PinHasher.verify("0042", legacyPlain));
        assertTrue(PinHasher.verify("0000", legacyZero));
        assertFalse(PinHasher.verify("0043", legacyPadded));
        assertTrue(PinHasher.needsUpgrade(legacyPadded));
    }

    @Test
    void garbageStoredHashNeverVerifies() {
        assertFalse(PinHasher.verify("1234", "pbkdf2$abc$???$???"));
        assertFalse(PinHasher.verify("1234", "pbkdf2$20000$onlytwo"));
        assertFalse(PinHasher.verify("1234", ""));
        assertFalse(PinHasher.verify(null, PinHasher.hash("1234")));
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // --- PinAttemptTracker ---------------------------------------------------

    @Test
    void accountLocksAfterMaxFailuresThenUnlocksAfterTheDelay() {
        AtomicLong now = new AtomicLong(1_000_000);
        PinAttemptTracker tracker = new PinAttemptTracker(now::get);

        assertEquals(0, tracker.remainingLockSeconds("ACC-1"));
        assertEquals(2, tracker.recordFailure("ACC-1", 3, 300_000));
        assertEquals(1, tracker.recordFailure("ACC-1", 3, 300_000));
        assertEquals(0, tracker.recordFailure("ACC-1", 3, 300_000));

        assertEquals(300, tracker.remainingLockSeconds("ACC-1"));
        now.addAndGet(100_500);
        assertEquals(200, tracker.remainingLockSeconds("ACC-1"));
        now.addAndGet(200_000);
        assertEquals(0, tracker.remainingLockSeconds("ACC-1"));
        // apres deverrouillage, on repart avec tous les essais
        assertEquals(2, tracker.recordFailure("ACC-1", 3, 300_000));
    }

    @Test
    void successResetsTheCounterAndAccountsAreIndependent() {
        PinAttemptTracker tracker = new PinAttemptTracker(() -> 0L);

        tracker.recordFailure("ACC-1", 3, 1000);
        tracker.recordFailure("ACC-1", 3, 1000);
        tracker.reset("ACC-1");
        assertEquals(2, tracker.recordFailure("ACC-1", 3, 1000));

        tracker.recordFailure("ACC-2", 1, 1000);
        assertTrue(tracker.remainingLockSeconds("ACC-2") > 0);
        assertEquals(0, tracker.remainingLockSeconds("ACC-1"));
    }
}
