package org.bb.bb_economy.database;

import org.bb.bb_economy.Msg;

/** Resultat d'une operation bancaire. Seul OK signifie que de l'argent a effectivement bouge. */
public enum TxResult {
    OK,
    INVALID_AMOUNT,
    NO_ACCOUNT,
    TARGET_NOT_FOUND,
    SAME_ACCOUNT,
    INSUFFICIENT_FUNDS,
    LIMIT_REACHED,
    BALANCE_OVERFLOW,
    NOT_ENOUGH_CASH,
    ACCOUNT_FROZEN,
    CARD_NOT_IN_HAND,
    SERVICE_UNAVAILABLE;

    /** Cle de traduction du message ({@code bb_economy.tx.<nom en minuscules>}), a envoyer au client. */
    public String message() {
        return Msg.of(Msg.PREFIX + "tx." + name().toLowerCase());
    }

    public boolean isOk() {
        return this == OK;
    }
}
