package org.bb.bb_economy.database;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Compte les PIN errones par compte et verrouille temporairement les essais apres trop d'echecs.
 * L'etat est en memoire : il se reinitialise au redemarrage du serveur.
 */
public final class PinAttemptTracker {

    private record State(int failures, long lockedUntilMillis) {
    }

    private final Map<String, State> states = new HashMap<>();
    private final LongSupplier clockMillis;

    public PinAttemptTracker() {
        this(System::currentTimeMillis);
    }

    public PinAttemptTracker(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Secondes restantes avant de pouvoir reessayer, 0 si les essais sont autorises. */
    public synchronized long remainingLockSeconds(String key) {
        State state = states.get(key);
        if (state == null || state.lockedUntilMillis() == 0) {
            return 0;
        }
        long remaining = state.lockedUntilMillis() - clockMillis.getAsLong();
        if (remaining <= 0) {
            states.remove(key);
            return 0;
        }
        return (remaining + 999) / 1000;
    }

    /** Enregistre un echec et retourne le nombre d'essais restants (0 = compte verrouille a l'instant). */
    public synchronized int recordFailure(String key, int maxAttempts, long lockoutMillis) {
        State state = states.get(key);
        int failures = state == null ? 0 : state.failures();
        failures++;
        if (failures >= Math.max(1, maxAttempts)) {
            states.put(key, new State(0, clockMillis.getAsLong() + lockoutMillis));
            return 0;
        }
        states.put(key, new State(failures, 0));
        return maxAttempts - failures;
    }

    public synchronized void reset(String key) {
        states.remove(key);
    }
}
