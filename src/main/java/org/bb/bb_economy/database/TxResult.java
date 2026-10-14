package org.bb.bb_economy.database;

/** Resultat d'une operation bancaire. Seul OK signifie que de l'argent a effectivement bouge. */
public enum TxResult {
    OK("Operation effectuee."),
    INVALID_AMOUNT("Montant invalide."),
    NO_ACCOUNT("Aucun compte bancaire n'est disponible."),
    TARGET_NOT_FOUND("Compte destinataire introuvable."),
    SAME_ACCOUNT("Le compte source et le compte destinataire sont identiques."),
    INSUFFICIENT_FUNDS("Solde insuffisant."),
    LIMIT_REACHED("Limite journaliere atteinte."),
    BALANCE_OVERFLOW("Le solde maximal du compte serait depasse."),
    NOT_ENOUGH_CASH("Pas assez de billets."),
    SERVICE_UNAVAILABLE("Service bancaire indisponible. Reessayez plus tard.");

    private final String message;

    TxResult(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public boolean isOk() {
        return this == OK;
    }
}
