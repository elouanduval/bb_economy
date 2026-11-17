# bb_economy

Mod **Forge 1.20.1** qui gère l'argent d'un serveur de jeu de rôle : comptes bancaires, cartes à PIN, distributeurs (ATM), terminaux de paiement (TPE), billets et portefeuille. Tout est stocké dans une base **MariaDB**.

Les entreprises, leurs membres et leurs rangs sont gérés par un autre composant, le plugin [bb_company_plugin](https://github.com/Boblche/bb_company_plugin). Ce mod ne fait que **lire** ses tables et y rattacher un compte bancaire à chaque entreprise.

## Fonctionnalités

- **Comptes personnels** : un compte par profil RP actif, créé par un administrateur, avec une carte bancaire.
- **Carte et PIN** : PIN à 4 chiffres haché (PBKDF2 avec sel), verrouillage temporaire après trop d'essais ratés, changement de PIN à l'ATM.
- **ATM** : dépôt et retrait de billets (montants entiers), virement vers un numéro de compte, plafonds journaliers par compte.
- **Entreprises** : chaque entreprise a un compte bancaire sans propriétaire, ouvert automatiquement au besoin. Le patron choisit à l'ATM entre son compte personnel et celui de ses entreprises.
- **TPE** : un membre de l'entreprise fixe un montant, le client paie avec sa carte et son PIN, l'argent va sur le compte de l'entreprise.
- **Boutiques automatiques** : un bloc sans vendeur, lié à une entreprise, où le joueur vend et achète les objets du **métier** de cette entreprise. C'est le compte de l'entreprise qui paie et encaisse. Paiement en liquide ou par carte, au choix du joueur.
- **Billets et portefeuille** : clic droit pour ranger les billets, accroupi + clic droit pour en retirer.
- **Grand livre** : chaque mouvement d'argent par carte est tracé dans `bb_transactions`.
- **Administration** : consultation, crédit/débit, historique, gel de compte, déblocage de PIN, réémission de carte.

## Prérequis

- Java 17
- Minecraft 1.20.1 avec Forge 47.4.x
- MariaDB 10.5 ou plus (testé avec 11)

## Installation

1. Démarrer une base MariaDB. Exemple avec Docker :

   ```bash
   docker run -d --name bb-economy-db -e MYSQL_ROOT_PASSWORD=password -e MYSQL_DATABASE=bb_economy -p 3306:3306 mariadb:11
   ```

2. Compiler le mod :

   ```bash
   ./gradlew build
   ```

   Le jar est produit dans `build/libs/bb_economy-<version>.jar`. Il embarque HikariCP et le pilote MariaDB.

3. Déposer le jar dans le dossier `mods/` du serveur **et** des clients : les deux doivent avoir **exactement la même version** (la version du protocole réseau est vérifiée à la connexion).

4. Au premier démarrage, le mod crée ses tables et applique les migrations nécessaires sur une base existante.

Pour lancer un serveur de développement : `./gradlew runServer`.

## Configuration

Fichier de configuration du mod, créé au premier lancement dans `config/bb_economy.toml` (dossier du serveur). Les valeurs sont lues au démarrage : redémarrer le serveur après une modification.

| Clé | Défaut | Rôle |
|---|---|---|
| `database.host` / `database.port` | `localhost` / `3306` | Serveur MariaDB |
| `database.name` | `bb_economy` | Nom de la base (la même que celle du plugin des entreprises) |
| `database.user` / `database.password` | `root` / `password` | Identifiants. **À changer en production.** |
| `economy.startingBankBalance` | `100.0` | Solde à la création d'un compte personnel |
| `economy.startingCashInInventory` | `0` | Billets remis à la création du compte |
| `economy.giveStarterWallet` / `economy.giveStarterCard` | `true` | Objets remis à la création du compte |
| `economy.defaultCardPin` | `0` | PIN initial (affiché `0000`), à remplacer par le joueur ; il ne peut pas être choisi comme nouveau PIN |
| `economy.dailyDepositLimit` / `dailyWithdrawLimit` / `dailyTransferLimit` | `10000.0` | Plafonds par compte et par jour de jeu |
| `economy.walletMaxStoredMoney` | `10000` | Capacité du portefeuille, en billets |
| `economy.pin.maxAttempts` | `3` | PIN erronés avant verrouillage |
| `economy.pin.lockoutSeconds` | `300` | Durée du verrouillage, en secondes réelles |

## Base de données

La base est **partagée** avec le plugin des entreprises. Chaque composant possède ses tables et ne modifie pas celles de l'autre.

| Table | Propriétaire | Rôle |
|---|---|---|
| `bb_bank_accounts` | ce mod | Comptes (solde, type `personal` ou `company`, état gelé) |
| `bb_bank_cards` | ce mod | Cartes et PIN hachés |
| `bb_transactions` | ce mod | Grand livre |
| `bb_company_accounts` | ce mod | Lien entreprise → compte bancaire |
| `bb_companies`, `bb_company_members`, `bb_ranks`, `bb_professions` | plugin des entreprises | Lues seulement (entreprise, patron via `owner_uuid`, membres) |
| `bb_shop_offers` | plugin des entreprises (création) | Offres des boutiques automatiques ; **ce mod y lit et y écrit les offres** (commandes `shop`) |
| `bb_shop_offer_bundles` | ce mod | Taille et prix exact du lot de chaque offre (ex. 32 bois pour 10 bb_euros), lié à l'id de l'offre |
| `mc_accounts`, `profiles` | mod d'identité | Profil RP actif du joueur ; créées si absentes |

Notes :

- Un compte personnel est rattaché à un **profil** (`profiles.id`). Supprimer un profil supprime en cascade son compte, ses cartes et son solde.
- Une entreprise ne peut pas être supprimée tant qu'elle a un compte bancaire (clé étrangère protégée).
- Une ancienne table `bb_companies` créée par une version précédente de ce mod est renommée en `bb_companies_legacy` au démarrage, sans rien supprimer. La table `bb_jobs` éventuelle n'est plus utilisée.

## Démarrage rapide (administrateur)

Les commandes demandent d'être opérateur (`/op <pseudo>`). L'ordre compte : **sans compte bancaire, un joueur ne peut rien faire** avec l'ATM, le TPE ou les boutiques.

1. **Le joueur doit avoir un profil RP actif** (géré par le mod d'identité, tables `profiles` et `mc_accounts`). Sans profil, la création du compte échoue.
2. **Créer son compte bancaire** : `/bb_economy bank create <joueur>`.
   Le joueur reçoit sa carte (et un portefeuille selon la config), son compte est crédité du solde de départ, et l'écran de choix du PIN s'ouvre. S'il le ferme sans choisir, il lui est reproposé à sa prochaine connexion.
3. **Le joueur choisit son PIN** : 4 chiffres, et pas `0000` (c'est le PIN provisoire).
4. **Poser un ATM** : `/give <joueur> bb_economy:atm`, puis poser le bloc. Le joueur tient sa **carte en main** et fait un clic droit dessus.
5. **Les entreprises** sont créées dans le plugin des entreprises. Côté argent, ouvrir leur compte : `/bb_economy company open_all_accounts` (ou `company open_account <entreprise>`). Un compte s'ouvre aussi tout seul au premier paiement TPE.
6. **Équiper un commerce** :
   - un TPE : `/bb_economy give tpe <entreprise>`, puis le poser ;
   - une boutique automatique : voir [Boutiques automatiques](#boutiques-automatiques).

Pour donner de l'argent de départ, `/bb_economy bank credit <joueur> <montant>`. Pour des billets en main, `/give <joueur> bb_economy:money <quantité>`.

## Utilisation en jeu (joueurs)

- **ATM** : carte en main, clic droit sur le distributeur, entrer son PIN, puis déposer, retirer, virer vers un numéro de compte (`ACC-…`) ou changer son PIN. Les montants de dépôt et de retrait sont entiers (un billet vaut 1). Des plafonds journaliers s'appliquent.
- **PIN** : après 3 essais ratés, le compte est verrouillé 5 minutes (réglable). Un administrateur peut lever le verrou.
- **Billets et portefeuille** : clic droit avec le portefeuille pour ranger les billets de l'inventaire, accroupi + clic droit pour en retirer.
- **TPE** : un **membre de l'entreprise** fait un clic droit sur le TPE, saisit le montant et valide. Le **client** fait ensuite un clic droit **carte en main**, entre son PIN et valide le paiement. L'argent va sur le compte de l'entreprise.
- **Boutique automatique** : clic droit sur le bloc, choisir une offre et un nombre de lots, payer en liquide ou par carte.
- **Patron d'une entreprise** : après le PIN, l'ATM propose « Compte personnel » ou le compte de chaque entreprise dont il est le patron. Il y dépose les recettes encaissées en liquide et paie ses employés par virement depuis le compte de l'entreprise.

## Commandes d'administration

Toutes demandent la permission de niveau 2. `/bb_economy help` les liste en jeu. Le nom d'une entreprise contenant des espaces se met entre guillemets.

| Commande | Effet |
|---|---|
| `bank create <joueur>` | Crée le compte et la carte |
| `bank reissue_card <joueur>` | Nouvelle carte (nouveau numéro, l'ancienne ne marche plus), PIN réinitialisé |
| `card regive <joueur>` | Redonne la carte actuelle |
| `bank unlock <joueur>` | Lève le verrouillage du PIN |
| `bank\|company info <cible>` | Numéro de compte, solde, état |
| `bank\|company credit\|debit <cible> <montant>` | Ajoute ou retire de l'argent (tracé) |
| `bank\|company history <cible> [n]` | Dernières transactions (10 par défaut, 50 au plus) |
| `bank\|company freeze\|unfreeze <cible>` | Gèle ou dégèle un compte (il ne peut plus rien émettre, il peut recevoir) |
| `company open_account <entreprise>` | Ouvre le compte d'une entreprise |
| `company open_all_accounts` | Ouvre les comptes de toutes les entreprises qui n'en ont pas |
| `give tpe <entreprise> [joueur]` | Donne un TPE lié à une entreprise |
| `give shop <entreprise> [joueur]` | Donne un bloc boutique automatique lié à une entreprise |
| `shop list <métier>` | Offres du métier (`sell` = le joueur vend à la boutique, `buy` = le joueur lui achète) |
| `shop set <métier> <sell\|buy> <quantité> <prix> [objet]` | Crée ou met à jour une offre : `<quantité>` objets pour `<prix>`. Objet tenu en main si omis |
| `shop remove\|enable\|disable <métier> <sell\|buy> [objet]` | Supprime, active ou désactive l'offre (objet tenu en main si omis) |

Objets vanilla : `/give <joueur> bb_economy:money`, `bb_economy:wallet`, `bb_economy:atm`.

Dans `/bb_economy help`, les commandes sont colorées (arguments obligatoires en cyan, facultatifs en cyan foncé) et un clic sur une commande la saisit dans le chat. `<cible>` désigne un joueur pour `bank` et une entreprise pour `company`.

**Cas courants**

| Situation | Commande |
|---|---|
| Un joueur n'a pas de compte | `bank create <joueur>` |
| Carte perdue, volée ou dupliquée | `bank reissue_card <joueur>` : nouveau numéro, l'ancienne carte ne marche plus, PIN remis à zéro |
| Le joueur a perdu l'objet mais pas la carte en base | `card regive <joueur>` |
| PIN bloqué après trop d'essais | `bank unlock <joueur>` |
| Corriger un solde | `bank credit <joueur> <montant>` ou `bank debit …` (tracé dans le grand livre) |
| Voir le solde, le numéro de compte | `bank info <joueur>` ou `company info <entreprise>` |
| Vérifier ce qui s'est passé | `bank history <joueur> [n]` |
| Bloquer un compte suspect | `bank freeze <joueur>` (il ne peut plus rien émettre mais peut recevoir), `unfreeze` pour lever |
| Donner son numéro de compte d'entreprise pour un virement | `company info <entreprise>` |

## Boutiques automatiques

Tant qu'aucun joueur ne tient un métier, un bloc boutique permet de vendre et d'acheter ses objets.

1. Créer l'entreprise de ce métier dans le plugin des entreprises (sans personnel si besoin), puis `company open_account` (ou laisser le compte s'ouvrir tout seul).
2. Définir les offres du métier. Une offre est un **lot** : `/bb_economy shop set SCIERIE sell 32 10 minecraft:oak_log` (le joueur vend 32 bûches de chêne pour 10) et `/bb_economy shop set SCIERIE buy 16 12 minecraft:oak_planks` (il achète 16 planches pour 12). L'objet est complété par Tab (mods compris) ou, s'il est omis, pris dans la main. Un objet peut n'être qu'achetable ou que vendable : on ne crée que le sens voulu.
3. Donner le bloc : `/bb_economy give shop "Scierie Nord"`, puis le poser.
4. Alimenter le compte de l'entreprise pour qu'elle puisse payer les ventes des joueurs : `/bb_economy company credit "Scierie Nord" 500`.

Fonctionnement :

- Les offres sont celles du **métier** de l'entreprise liée : toutes les entreprises d'un même métier partagent leurs offres.
- Dans l'écran, le joueur choisit un **nombre de lots** : 3 lots de « 32 pour 10 » = 96 objets pour 30 bb_euros (4096 objets au plus par échange).
- Le joueur choisit liquide (billets de l'inventaire) ou carte (PIN valide et carte en main). Le liquide exige un total entier.
- Quand le joueur **vend**, le compte de l'entreprise est débité ; s'il est trop bas, la vente est refusée. Quand il **achète**, le compte est crédité.
- Tous les mouvements du compte de l'entreprise sont tracés dans le grand livre (types `SHOP_SELL`, `SHOP_BUY`, `SHOP_SELL_CASH`, `SHOP_BUY_CASH`).
- Seuls les objets sans données ni usure sont repris ; les objets vendus par la boutique sont créés à la demande (stock illimité).
- Pour fermer une boutique quand un joueur reprend le métier : `shop disable` sur ses offres.

## Langues

Les textes vus par les joueurs (ATM, TPE, boutiques, messages, infobulles, noms des objets et blocs) existent en français (`fr_fr.json`) et en anglais (`en_us.json`), dans `src/main/resources/assets/bb_economy/lang/`. Le client affiche la langue choisie dans Minecraft ; une langue absente retombe sur l'anglais.

Pour ajouter une langue : copier `en_us.json` en `<code_langue>.json` (ex. `es_es.json`) et traduire les valeurs. Les `%s` sont remplacés dans l'ordre, il faut les conserver. Le test `LangFilesTest` vérifie qu'aucune clé utilisée dans le code ne manque et que le français et l'anglais ont les mêmes clés.

Le serveur n'envoie pas de texte mais une clé de traduction (classe `Msg`) : c'est le client qui la traduit. Les commandes d'administration et les journaux du serveur restent en français.

## Architecture

```
org.bb.bb_economy
├── database/   BankLedger (cœur monétaire), BankManager (façade Minecraft), ShopManager et
│               ShopCatalog (boutiques automatiques), DbExecutor,
│               Money, PinHasher, PinAttemptTracker, DatabaseManager (schéma et migrations)
├── command/    AdminCommands
├── network/    Paquets client/serveur (PIN, ATM, TPE)
├── gui/        Écrans et menus ATM / TPE
├── blocks/ item/ init/   Blocs, objets et enregistrements
```

- **`BankLedger` n'a aucune dépendance à Minecraft** : chaque opération est une transaction SQL unique, les comptes sont verrouillés (`FOR UPDATE`) dans un ordre fixe, et les plafonds journaliers sont vérifiés sous verrou. Il est testé sur une base H2 en mémoire.
- **Appels base hors du thread principal** : les actions de jeu (ATM, TPE, PIN) envoient la requête SQL sur un pool dédié (`DbExecutor`), puis reviennent sur le thread principal pour toucher au joueur, à l'inventaire ou au bloc. Les commandes d'administration, rares, restent synchrones.
- **Cartes** : le compte utilisé à l'ATM est celui de la carte tenue en main, qui doit appartenir au profil actif du joueur.

## Tests

```bash
./gradlew test
```

Ils couvrent le grand livre (retraits, virements, plafonds, gel, concurrence), le catalogue des offres, les montants, le hachage du PIN et l'arbre des commandes. L'interface en jeu n'est pas couverte par des tests automatiques.

## Intégration avec le plugin des entreprises

- Le plugin est un plugin Paper/Spigot : il ne communique avec ce mod que par la **base de données** (un serveur hybride de type Mohist ou Arclight est prévu de son côté).
- L'appartenance à une entreprise vient de `bb_company_members` ; le patron est `bb_companies.owner_uuid`.
- Les paiements des boutiques du plugin doivent passer par le grand livre de ce mod, pas par une écriture directe dans les soldes. Le mécanisme reste à définir.

## Limites connues et pistes

- Pas de commande de solde ni d'historique côté joueur (l'ATM affiche le solde).
- Les messages sont écrits en dur en français ; seul `en_us.json` existe pour les noms d'objets.
- La colonne `unit_price` des offres est un `FLOAT` côté plugin : ce mod y écrit le prix d'un objet à titre informatif, mais lit le prix exact du lot dans `bb_shop_offer_bundles`.
- Les boutiques automatiques n'ont pas de stock ni de plafond par joueur ; seul le solde du compte de l'entreprise limite ce qu'elles achètent.
- Le journal de détail des ventes du plugin (`bb_shop_transactions`) n'est pas relié au grand livre.
- Le verrouillage du PIN est en mémoire : il est remis à zéro au redémarrage du serveur.

## Licence

Tous droits réservés. Auteur : BB_Elouan121.
