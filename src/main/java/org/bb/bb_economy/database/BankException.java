package org.bb.bb_economy.database;

/** Erreur d'infrastructure (base de donnees indisponible, etc.), distincte d'un refus metier (TxResult). */
public class BankException extends RuntimeException {

    public BankException(String message) {
        super(message);
    }

    public BankException(String message, Throwable cause) {
        super(message, cause);
    }
}
