package org.bb.bb_economy.database;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hachage des codes PIN : PBKDF2-HMAC-SHA256 avec sel aleatoire par carte.
 * Les anciens hash (SHA-256 nu) restent verifiables afin d'etre migres a la premiere connexion reussie.
 *
 * Un PIN a 4 chiffres n'a que 10 000 valeurs : ce hachage ralentit et individualise une attaque hors-ligne
 * mais ne la rend pas impossible. La vraie protection en ligne est le verrouillage apres echecs.
 */
public final class PinHasher {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 20_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PinHasher() {
    }

    public static String hash(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(pin, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    public static boolean verify(String pin, String stored) {
        if (pin == null || stored == null) {
            return false;
        }
        if (stored.startsWith(PREFIX + "$")) {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                return MessageDigest.isEqual(expected, derive(pin, salt, iterations));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return verifyLegacy(pin, stored);
    }

    /** Vrai si le hash stocke doit etre recalcule (ancien format ou moins d'iterations que l'actuel). */
    public static boolean needsUpgrade(String stored) {
        if (stored == null || !stored.startsWith(PREFIX + "$")) {
            return true;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Anciens formats : SHA-256 de "0042" ou de "42" (l'ancienne version ne remplissait pas de zeros). */
    private static boolean verifyLegacy(String pin, String stored) {
        final String plain;
        try {
            plain = String.valueOf(Integer.parseInt(pin));
        } catch (NumberFormatException e) {
            return false;
        }
        return MessageDigest.isEqual(sha256Hex(pin).getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8))
                || MessageDigest.isEqual(sha256Hex(plain).getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private static byte[] derive(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponible", e);
        }
    }
}
