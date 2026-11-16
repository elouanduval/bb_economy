package org.bb.bb_economy.database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Catalogue des offres des boutiques automatiques : lecture et gestion de {@code bb_shop_offers}, table creee
 * par le plugin des entreprises (ce mod ne la cree pas). Aucune dependance a Minecraft, pour etre testable.
 *
 * Convention (point de vue du JOUEUR) : direction {@code sell} = le joueur vend l'objet a la boutique,
 * direction {@code buy} = le joueur achete l'objet a la boutique. Une offre appartient a un METIER ; une
 * entreprise y accede par sa colonne {@code profession_code}.
 *
 * Une offre porte sur un LOT : {@code bundleSize} objets pour {@code bundlePrice} (ex. 32 bois pour 10).
 * La table du plugin n'a qu'un {@code unit_price} FLOAT, trop imprecis pour de l'argent (10 / 32 = 0,3125) : le lot
 * et son prix exact sont donc dans notre propre table {@code bb_shop_offer_bundles}, liee par l'id de l'offre. Sans
 * ligne de lot (offre creee par un autre outil), l'offre vaut 1 objet au prix {@code unit_price}.
 */
public final class ShopCatalog {

    public enum Direction {
        /** Le joueur vend a la boutique (colonne {@code direction} = 'sell'). */
        PLAYER_SELLS("sell"),
        /** Le joueur achete a la boutique (colonne {@code direction} = 'buy'). */
        PLAYER_BUYS("buy");

        private final String column;

        Direction(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }

        public static Optional<Direction> fromColumn(String value) {
            for (Direction direction : values()) {
                if (direction.column.equalsIgnoreCase(value)) {
                    return Optional.of(direction);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Une offre : {@code bundleSize} exemplaires d'un objet (identifiant Minecraft complet, ex.
     * {@code minecraft:oak_log}) pour {@code bundlePrice} au total.
     */
    public record Offer(int id, String professionCode, String itemKey, Direction direction,
                        int bundleSize, BigDecimal bundlePrice, boolean active) {
    }

    /** Plafond de la taille d'un lot. */
    public static final int MAX_BUNDLE_SIZE = 1024;

    private static final String COLUMNS = "o.id, o.profession_code, o.item_key, o.direction, "
            + "COALESCE(b.bundle_size, 1), COALESCE(b.bundle_price, o.unit_price), o.active";
    private static final String FROM = " FROM bb_shop_offers o LEFT JOIN bb_shop_offer_bundles b ON b.offer_id = o.id";

    private final BankLedger.ConnectionSource source;

    public ShopCatalog(BankLedger.ConnectionSource source) {
        this.source = source;
    }

    /** Metier d'une entreprise (colonne profession_code de la table des entreprises), vide si elle n'existe pas. */
    public Optional<String> professionOfCompany(int companyId) {
        return read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT profession_code FROM bb_companies WHERE id = ?")) {
                stmt.setInt(1, companyId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    public boolean professionExists(String professionCode) {
        return read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM bb_professions WHERE code = ?")) {
                stmt.setString(1, professionCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** Offres actives du metier d'une entreprise, triees par objet puis par sens. */
    public List<Offer> activeOffersOfCompany(int companyId) {
        return read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + COLUMNS + FROM + " JOIN bb_companies c ON c.profession_code = o.profession_code "
                            + "WHERE c.id = ? AND o.active = TRUE ORDER BY o.item_key, o.direction")) {
                stmt.setInt(1, companyId);
                return readOffers(stmt);
            }
        });
    }

    /** Une offre active precise, seulement si elle appartient bien au metier de l'entreprise. */
    public Optional<Offer> activeOfferOfCompany(int companyId, int offerId) {
        return read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + COLUMNS + FROM + " JOIN bb_companies c ON c.profession_code = o.profession_code "
                            + "WHERE c.id = ? AND o.id = ? AND o.active = TRUE")) {
                stmt.setInt(1, companyId);
                stmt.setInt(2, offerId);
                List<Offer> offers = readOffers(stmt);
                return offers.isEmpty() ? Optional.<Offer>empty() : Optional.of(offers.get(0));
            }
        });
    }

    /** Toutes les offres d'un metier, actives ou non (administration). */
    public List<Offer> offersOfProfession(String professionCode) {
        return read(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT " + COLUMNS + FROM + " WHERE o.profession_code = ? ORDER BY o.item_key, o.direction")) {
                stmt.setString(1, professionCode);
                return readOffers(stmt);
            }
        });
    }

    /**
     * Cree l'offre (metier, objet, sens) ou met son lot et son prix a jour, et l'active. Le prix du lot est arrondi
     * a 2 decimales. Retourne vrai si l'offre existait deja (mise a jour), faux si elle vient d'etre creee.
     */
    public boolean upsertOffer(String professionCode, String itemKey, Direction direction, int bundleSize, BigDecimal bundlePrice) {
        if (bundleSize < 1 || bundleSize > MAX_BUNDLE_SIZE) {
            throw new IllegalArgumentException("Taille de lot invalide : " + bundleSize);
        }
        BigDecimal price = bundlePrice.setScale(2, RoundingMode.HALF_UP);
        // Prix d'un objet, pour la colonne du plugin (informatif : le prix exact est celui du lot).
        double unitPrice = price.divide(BigDecimal.valueOf(bundleSize), 4, RoundingMode.HALF_UP).doubleValue();
        return write(conn -> {
            boolean existed;
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE bb_shop_offers SET unit_price = ?, active = TRUE "
                            + "WHERE profession_code = ? AND item_key = ? AND direction = ?")) {
                update.setDouble(1, unitPrice);
                update.setString(2, professionCode);
                update.setString(3, itemKey);
                update.setString(4, direction.column());
                existed = update.executeUpdate() > 0;
            }
            if (!existed) {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO bb_shop_offers (profession_code, item_key, direction, unit_price, active) VALUES (?, ?, ?, ?, TRUE)")) {
                    insert.setString(1, professionCode);
                    insert.setString(2, itemKey);
                    insert.setString(3, direction.column());
                    insert.setDouble(4, unitPrice);
                    insert.executeUpdate();
                }
            }
            int offerId = offerId(conn, professionCode, itemKey, direction);
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE bb_shop_offer_bundles SET bundle_size = ?, bundle_price = ? WHERE offer_id = ?")) {
                update.setInt(1, bundleSize);
                update.setBigDecimal(2, price);
                update.setInt(3, offerId);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO bb_shop_offer_bundles (offer_id, bundle_size, bundle_price) VALUES (?, ?, ?)")) {
                        insert.setInt(1, offerId);
                        insert.setInt(2, bundleSize);
                        insert.setBigDecimal(3, price);
                        insert.executeUpdate();
                    }
                }
            }
            return existed;
        });
    }

    private static int offerId(Connection conn, String professionCode, String itemKey, Direction direction) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id FROM bb_shop_offers WHERE profession_code = ? AND item_key = ? AND direction = ?")) {
            stmt.setString(1, professionCode);
            stmt.setString(2, itemKey);
            stmt.setString(3, direction.column());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Offre introuvable apres ecriture");
                }
                return rs.getInt(1);
            }
        }
    }

    /** Active ou desactive une offre. Faux si elle n'existe pas. */
    public boolean setActive(String professionCode, String itemKey, Direction direction, boolean active) {
        return write(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE bb_shop_offers SET active = ? WHERE profession_code = ? AND item_key = ? AND direction = ?")) {
                stmt.setBoolean(1, active);
                stmt.setString(2, professionCode);
                stmt.setString(3, itemKey);
                stmt.setString(4, direction.column());
                return stmt.executeUpdate() > 0;
            }
        });
    }

    /** Supprime une offre (et son lot). Faux si elle n'existe pas. */
    public boolean removeOffer(String professionCode, String itemKey, Direction direction) {
        return write(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM bb_shop_offer_bundles WHERE offer_id IN "
                            + "(SELECT id FROM bb_shop_offers WHERE profession_code = ? AND item_key = ? AND direction = ?)")) {
                stmt.setString(1, professionCode);
                stmt.setString(2, itemKey);
                stmt.setString(3, direction.column());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM bb_shop_offers WHERE profession_code = ? AND item_key = ? AND direction = ?")) {
                stmt.setString(1, professionCode);
                stmt.setString(2, itemKey);
                stmt.setString(3, direction.column());
                return stmt.executeUpdate() > 0;
            }
        });
    }

    // -------------------------------------------------------------------------

    private static List<Offer> readOffers(PreparedStatement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            List<Offer> offers = new ArrayList<>();
            while (rs.next()) {
                Optional<Direction> direction = Direction.fromColumn(rs.getString(4));
                if (direction.isEmpty()) {
                    continue; // valeur inconnue : on ignore la ligne plutot que de deviner un sens
                }
                // Sans lot, le prix vient de unit_price (FLOAT dans la table du plugin) : on arrondit a 2 decimales.
                BigDecimal price = BigDecimal.valueOf(rs.getDouble(6)).setScale(2, RoundingMode.HALF_UP);
                offers.add(new Offer(rs.getInt(1), rs.getString(2), rs.getString(3), direction.get(),
                        rs.getInt(5), price, rs.getBoolean(7)));
            }
            return offers;
        }
    }

    @FunctionalInterface
    private interface Body<T> {
        T run(Connection conn) throws SQLException;
    }

    private <T> T read(Body<T> body) {
        try (Connection conn = source.open()) {
            return body.run(conn);
        } catch (SQLException e) {
            throw new BankException("Erreur base de donnees : " + e.getMessage(), e);
        }
    }

    private <T> T write(Body<T> body) {
        try (Connection conn = source.open()) {
            conn.setAutoCommit(false);
            try {
                T result = body.run(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new BankException("Erreur base de donnees : " + e.getMessage(), e);
        }
    }
}
