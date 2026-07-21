package org.bb.bb_economy.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bb.bb_economy.Config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private static HikariDataSource dataSource;

    public static void init() {
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
            System.out.println("[BB Economy] Pool HikariCP connecte a "
                    + Config.DB_HOST + "/" + Config.DB_NAME);
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

            // owner est maintenant un INT referençant profiles.id
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_bank_accounts (
                    account_number  VARCHAR(20)   NOT NULL,
                    account_type    VARCHAR(20)   NOT NULL DEFAULT 'personal',
                    account_balance DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    owner           INT           NOT NULL,
                    PRIMARY KEY (account_number),
                    INDEX idx_owner (owner),
                    CONSTRAINT fk_account_profile FOREIGN KEY (owner)
                        REFERENCES profiles (id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_bank_cards (
                    card_number    VARCHAR(20) NOT NULL,
                    card_pin       VARCHAR(64) NOT NULL,
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
                CREATE INDEX IF NOT EXISTS idx_tx_type_day_origin
                ON bb_transactions (transaction_type, transaction_ingame_day, account_origin)
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_tx_type_day_target
                ON bb_transactions (transaction_type, transaction_ingame_day, account_target)
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_companies (
                    company_id     VARCHAR(20) NOT NULL,
                    account_number VARCHAR(20) NOT NULL,
                    PRIMARY KEY (company_id),
                    UNIQUE KEY uq_company_account (account_number),
                    CONSTRAINT fk_company_account FOREIGN KEY (account_number)
                        REFERENCES bb_bank_accounts (account_number) ON DELETE RESTRICT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // owner est maintenant un INT referençant profiles.id
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bb_jobs (
                    job_id          VARCHAR(20)   NOT NULL,
                    job_name        VARCHAR(50)   NOT NULL,
                    job_rank        INT           NOT NULL DEFAULT 2,
                    job_salary      DECIMAL(10,2) NOT NULL DEFAULT 0.00,
                    job_salary_time INT           NOT NULL DEFAULT 0,
                    owner           INT           NOT NULL,
                    company_id      VARCHAR(20)   NOT NULL,
                    PRIMARY KEY (job_id),
                    INDEX idx_job_owner   (owner),
                    INDEX idx_job_company (company_id),
                    INDEX idx_salary_time (job_salary_time),
                    CONSTRAINT fk_job_profile FOREIGN KEY (owner)
                        REFERENCES profiles (id) ON DELETE CASCADE,
                    CONSTRAINT fk_job_company FOREIGN KEY (company_id)
                        REFERENCES bb_companies (company_id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            System.out.println("[BB Economy] Tables verifiees/creees.");

        } catch (SQLException e) {
            throw new RuntimeException("[BB Economy] Erreur creation tables : " + e.getMessage(), e);
        }
    }

    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("[BB Economy] DatabaseManager non initialise !");
        }
        return dataSource.getConnection();
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("[BB Economy] Pool HikariCP ferme.");
        }
    }

    public static boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}