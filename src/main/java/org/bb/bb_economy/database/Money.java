package org.bb.bb_economy.database;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Regles strictes sur les montants. Tout montant venant d'un client passe par ici :
 * pas de notation scientifique, pas de signe, 2 decimales maximum, plafond du type DECIMAL(10,2).
 */
public final class Money {

    /** Plus grand montant representable par les colonnes DECIMAL(10,2). */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,8}(?:\\.\\d{1,2})?");

    private Money() {
    }

    /** Parse un montant strictement positif saisi par un joueur, ou vide s'il est invalide. */
    public static Optional<BigDecimal> parsePositive(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().replace(',', '.');
        if (!AMOUNT_PATTERN.matcher(text).matches()) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(text).setScale(2);
        return isValid(value) ? Optional.of(value) : Optional.empty();
    }

    /** Un montant est valide s'il est > 0, <= MAX_AMOUNT et a au plus 2 decimales. */
    public static boolean isValid(BigDecimal amount) {
        return amount != null
                && amount.signum() > 0
                && amount.scale() <= 2
                && amount.compareTo(MAX_AMOUNT) <= 0;
    }

    /** Vrai si le montant correspond a un nombre entier de billets. */
    public static boolean isWhole(BigDecimal amount) {
        return amount != null && amount.stripTrailingZeros().scale() <= 0;
    }

    /** Nombre de billets correspondant a un montant entier valide. */
    public static int toBills(BigDecimal amount) {
        return amount.setScale(0).intValueExact();
    }
}
