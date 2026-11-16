package org.bb.bb_economy.database;

import com.mojang.logging.LogUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bb.bb_economy.Config;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile HikariDataSource dataSource;

    public static synchronized void init() {
        close();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mariadb://" + Config.DB_HOST + ":" + Config.DB_PORT
                + "/" + Config.DB_NAME + "?useUnicode=true&characterEncoding=utf8mb4");
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setUsername(Config.DB_USER);
        config.setPassword(Config.DB_PASSWORD);

        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(1800000);
        config.setPoolName("BBEconomyPool");

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        try {
            dataSource = new HikariDataSource(config);
            LOGGER.info("Pool HikariCP connecte a {}/{}", Config.DB_HOST, Config.DB_NAME);
            createTables();
        } catch (Exception e) {
            throw new RuntimeException(
                    "[BB Economy] Impossible de se connecter a MariaDB : " + e.getMessage(), e);
        }
    }

    private static void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            // Tables externes (gerees par un autre mod) - creees si absentes uniquement
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS mc_accounts (
                    uuid            VARCHAR(36)  NOT NULL PRIMARY KEY,
                    username        VARCHAR(16)  NOT NULL,
                    active_profile  INT          DEFAULT NULL,
                    created_at      DATETIME     NOT NULL DEFAULT NOW(),
                    updated_at      DATETIME     NOT NULL DEFAULT NOW() ON UPDATE NOW()
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS profiles (
                    id              INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    uuid            VARCHAR(36)  NOT NULL,
                    firstname       VARCHAR(20)  NOT NULL,
                    lastname        VARCHAR(20)  NOT NULL,
                    birth_date      DATE         NOT NULL,
                    gender          ENUM('male','female','other') NOT NULL,
                    city            VARCHAR(64)  NOT NULL,
                    inventory       JSON         DEFAULT NULL,
                    pos_world       VARCHAR(50)  NOT NULL DEFAULT 'world',
                    pos_x           DOUBLE       NOT NULL DEFAULT 0,
                    pos_y           DOUBLE       NOT NULL DEFAULT 64,
                    pos_z           DOUBLE       NOT NULL DEFAULT 0,
                    pos_yaw         FLOAT        NOT NULL DEFAULT 0,
                    pos_pitch       FLOAT        NOT NULL DEFAULT 0,
                    health          DOUBLE       NOT NULL DEFAULT 20.0,
                    food_level      INT          NOT NULL DEFAULT 20,
                    xp              FLOAT        NOT NULL DEFAULT 0,
                    tamed_entities  JSON         DEFAULT NULL,
                    life_status     ENUM('alive','dead') NOT NULL DEFAULT 'alive',
                    active          BOOLEAN      NOT NULL DEFAULT TRUE,
                    created_at      DATETIME     NOT NULL DEFAULT NOW(),
                    updated_at      DATETIME     NOT NULL DEFAULT NOW() ON UPDATE NOW(),
                    CONSTRAINT fk_profile_account FOREIGN KEY (uuid) REFERENCES mc_accounts(uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // owner : profil proprietaire d'un compte PERSONNEL (profiles.id). NULL pour un compte d'entreprise, qui peut
            // exister sans aucun personnel : son rattachement passe par bb_company_accounts.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_bank_accounts (
                    account_number  VARCHAR(20)   NOT NULL,
                    account_type    VARCHAR(20)   NOT NULL DEFAULT 'personal',
                    account_balance DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    owner           INT           NULL,
                    frozen          BOOLEAN       NOT NULL DEFAULT FALSE,
                    PRIMARY KEY (account_number),
                    INDEX idx_owner (owner),
                    CONSTRAINT fk_account_profile FOREIGN KEY (owner)
                        REFERENCES profiles (id) ON DELETE CASCADE,
                    CONSTRAINT chk_balance_non_negative CHECK (account_balance >= 0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_bank_cards (
                    card_number    VARCHAR(20) NOT NULL,
                    card_pin       VARCHAR(255) NOT NULL,
                    account_number VARCHAR(20) NOT NULL,
                    card_last_item_given_at  DATETIME NULL,
                    card_last_item_receiver  VARCHAR(50) NULL,
                    PRIMARY KEY (card_number),
                    CONSTRAINT fk_card_account
                        FOREIGN KEY (account_number)
                        REFERENCES bb_bank_accounts (account_number)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_transactions (
                    transaction_id         INT           NOT NULL AUTO_INCREMENT,
                    transaction_type       VARCHAR(20)   NOT NULL,
                    transaction_amount     DECIMAL(10,2) NOT NULL,
                    transaction_date       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    transaction_ingame_day BIGINT        NOT NULL DEFAULT 0,
                    account_origin         VARCHAR(20)   NULL,
                    account_target         VARCHAR(20)   NULL,
                    PRIMARY KEY (transaction_id),
                    INDEX idx_origin (account_origin),
                    INDEX idx_target (account_target),
                    CONSTRAINT fk_tx_origin FOREIGN KEY (account_origin)
                        REFERENCES bb_bank_accounts (account_number) ON DELETE SET NULL,
                    CONSTRAINT fk_tx_target FOREIGN KEY (account_target)
                        REFERENCES bb_bank_accounts (account_number) ON DELETE SET NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_date
                ON bb_transactions (transaction_date)
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_tx_type_day_origin
                ON bb_transactions (transaction_type, transaction_ingame_day, account_origin)
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_tx_type_day_target
                ON bb_transactions (transaction_type, transaction_ingame_day, account_target)
            """);

            // Les entreprises (bb_companies), leurs membres et leurs rangs appartiennent a un autre mod, qui cree
            // ses propres tables. Ici on ne possede que le LIEN entre une entreprise et son compte bancaire.
            // company_id est l'id (INT) de bb_companies ; la cle etrangere vers cette table est ajoutee plus bas,
            // des qu'elle existe (l'ordre de demarrage des deux mods n'est pas garanti).
            // ON DELETE RESTRICT : on ne peut pas supprimer un compte qui a encore un lien, pour ne jamais
            // laisser de l'argent orphelin.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_company_accounts (
                    company_id     INT         NOT NULL,
                    account_number VARCHAR(20) NOT NULL,
                    PRIMARY KEY (company_id),
                    UNIQUE KEY uq_company_account (account_number),
                    CONSTRAINT fk_company_account_bank FOREIGN KEY (account_number)
                        REFERENCES bb_bank_accounts (account_number) ON DELETE RESTRICT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // Lot d'une offre de boutique automatique (ex. 32 bois pour 10). La table des offres appartient au
            // plugin des entreprises et son prix est un FLOAT : le lot et son prix exact sont ici, lies par l'id de
            // l'offre. Pas de cle etrangere : la table du plugin peut ne pas exister encore au demarrage.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_shop_offer_bundles (
                    offer_id     INT           NOT NULL,
                    bundle_size  INT           NOT NULL,
                    bundle_price DECIMAL(12,2) NOT NULL,
                    PRIMARY KEY (offer_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // Les salaires ne sont plus geres par bb_economy (le patron paie a la main). Sur une base creee par une
            // ancienne version, la table bb_jobs existante n'est pas supprimee automatiquement : elle est ignoree.

            migrateExistingTables(stmt);

            LOGGER.info("Tables verifiees/creees.");

        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur creation tables : " + e.getMessage(), e);
        }
    }

    /**
     * CREATE TABLE IF NOT EXISTS ne modifie pas les tables deja presentes : on applique ici,
     * de facon idempotente, les changements de schema necessaires aux bases creees par une ancienne version.
     */
    private static void migrateExistingTables(Statement stmt) {
        // Les hash PBKDF2 sont plus longs que l'ancien SHA-256 (64 caracteres).
        runMigration(stmt, "ALTER TABLE bb_bank_cards MODIFY card_pin VARCHAR(255) NOT NULL");
        // Dernier filet de securite : la base refuse tout solde negatif meme en cas de bug applicatif.
        runMigration(stmt, "ALTER TABLE bb_bank_accounts ADD CONSTRAINT chk_balance_non_negative CHECK (account_balance >= 0)");

        // Un compte d'entreprise n'a pas de proprietaire (owner NULL) : la colonne etait NOT NULL dans les anciennes versions.
        runMigration(stmt, "ALTER TABLE bb_bank_accounts MODIFY owner INT NULL");

        // Gel de compte (administration) : colonne absente des bases creees par une ancienne version.
        runMigration(stmt, "ALTER TABLE bb_bank_accounts ADD COLUMN IF NOT EXISTS frozen BOOLEAN NOT NULL DEFAULT FALSE");

        renameLegacyCompaniesTable(stmt);
        // Cle etrangere vers la table des entreprises de l'autre mod : echoue (et sera retentee au prochain
        // demarrage) tant que cette table n'existe pas, ou si elle est deja posee.
        runMigration(stmt, "ALTER TABLE bb_company_accounts ADD CONSTRAINT fk_company_account_company "
                + "FOREIGN KEY (company_id) REFERENCES bb_companies (id) ON DELETE RESTRICT");
    }

    /**
     * Les anciennes versions de ce mod creaient leur propre bb_companies (company_id texte + account_number).
     * Ce nom appartient desormais a l'autre mod : tant que l'ancienne table est la, son CREATE TABLE IF NOT
     * EXISTS serait ignore. On la renomme (sans rien supprimer) pour liberer le nom ; ses donnees sont a
     * reporter a la main dans bb_company_accounts.
     */
    private static void renameLegacyCompaniesTable(Statement stmt) {
        try (ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'bb_companies' AND column_name = 'company_id'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                stmt.execute("RENAME TABLE bb_companies TO bb_companies_legacy");
                LOGGER.warn("Ancienne table bb_companies renommee en bb_companies_legacy (le nom est reserve a l'autre mod). "
                        + "Reportez ses lignes (company_id -> account_number) dans bb_company_accounts avec l'id de la "
                        + "nouvelle entreprise.");
            }
        } catch (SQLException e) {
            LOGGER.warn("Verification de l'ancienne table bb_companies impossible : {}", e.getMessage());
        }
    }

    private static void runMigration(Statement stmt, String sql) {
        try {
            stmt.execute(sql);
        } catch (SQLException e) {
            // Contrainte deja presente (cas normal apres la premiere migration) ou donnees existantes qui la violent.
            LOGGER.debug("Migration ignoree ({}) : {}", sql, e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("[BB Economy] DatabaseManager non initialise !");
        }
        return dataSource.getConnection();
    }

    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOGGER.info("Pool HikariCP ferme.");
        }
    }

    public static boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}