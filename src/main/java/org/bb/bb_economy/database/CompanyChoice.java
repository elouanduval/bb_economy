package org.bb.bb_economy.database;

/**
 * Une entreprise dont le joueur est le patron, proposee comme compte a l'ATM. Classe a part (et non imbriquee
 * dans BankManager) car elle est aussi envoyee au client, qui ne doit pas avoir a charger la facade serveur.
 */
public record CompanyChoice(int id, String name) {
}
