package org.bb.bb_economy.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private ShopCatalog catalog;
    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:shop" + DB_COUNTER.incrementAndGet() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        catalog = new ShopCatalog(() -> DriverManager.getConnection(url));
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            // Schema du plugin des entreprises (tables qu'il possede ; ce mod ne fait que les lire / gerer les offres).
            stmt.execute("CREATE TABLE bb_professions (code VARCHAR(32) NOT NULL PRIMARY KEY, label VARCHAR(64) NOT NULL)");
            stmt.execute("""
                    CREATE TABLE bb_companies (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(128) NOT NULL,
                        profession_code VARCHAR(32) NOT NULL,
                        owner_uuid VARCHAR(36) NOT NULL
                    )""");
            stmt.execute("""
                    CREATE TABLE bb_shop_offers (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        profession_code VARCHAR(32) NOT NULL,
                        item_key VARCHAR(64) NOT NULL,
                        direction VARCHAR(4) NOT NULL DEFAULT 'buy',
                        unit_price FLOAT NOT NULL,
                        active BOOLEAN NOT NULL
                    )""");
            stmt.execute("CREATE TABLE bb_shop_offer_bundles (offer_id INT NOT NULL PRIMARY KEY, bundle_size INT NOT NULL, bundle_price DECIMAL(12,2) NOT NULL)");
            stmt.execute("INSERT INTO bb_professions VALUES ('BUCHERON','Bucheron'),('MAIRIE','Mairie')");
            stmt.execute("INSERT INTO bb_companies (name, profession_code, owner_uuid) VALUES "
                    + "('Scierie Nord','BUCHERON','a'),('Scierie Sud','BUCHERON','b'),('Mairie','MAIRIE','c')");
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void companyResolvesToItsProfession() {
        assertEquals("BUCHERON", catalog.professionOfCompany(1).orElseThrow());
        assertEquals("MAIRIE", catalog.professionOfCompany(3).orElseThrow());
        assertTrue(catalog.professionOfCompany(99).isEmpty());
        assertTrue(catalog.professionExists("BUCHERON"));
        assertFalse(catalog.professionExists("INCONNU"));
    }

    @Test
    void upsertCreatesThenUpdatesTheSameOfferAndReactivatesIt() {
        assertFalse(catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("2")));
        assertTrue(catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("2.5")));

        List<ShopCatalog.Offer> offers = catalog.offersOfProfession("BUCHERON");
        assertEquals(1, offers.size(), "la meme offre ne doit pas etre dupliquee");
        assertEquals(0, bd("2.50").compareTo(offers.get(0).bundlePrice()));
        assertTrue(offers.get(0).active());
    }

    @Test
    void sameItemCanHaveABuyAndASellOfferAtDifferentPrices() {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_planks", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("1"));
        catalog.upsertOffer("BUCHERON", "minecraft:oak_planks", ShopCatalog.Direction.PLAYER_BUYS, 1, bd("3"));

        List<ShopCatalog.Offer> offers = catalog.activeOffersOfCompany(1);
        assertEquals(2, offers.size());
    }

    @Test
    void offersAreSharedByEveryCompanyOfTheProfessionAndNotByOthers() {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("2"));

        assertEquals(1, catalog.activeOffersOfCompany(1).size());
        assertEquals(1, catalog.activeOffersOfCompany(2).size());
        assertTrue(catalog.activeOffersOfCompany(3).isEmpty(), "la mairie n'a pas les offres du bucheron");
    }

    @Test
    void inactiveOffersAreHiddenFromPlayersButVisibleToAdminsAndCanBeReactivated() {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("2"));

        assertTrue(catalog.setActive("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, false));
        assertTrue(catalog.activeOffersOfCompany(1).isEmpty());
        assertEquals(1, catalog.offersOfProfession("BUCHERON").size());
        assertFalse(catalog.offersOfProfession("BUCHERON").get(0).active());

        assertTrue(catalog.setActive("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, true));
        assertEquals(1, catalog.activeOffersOfCompany(1).size());
        assertFalse(catalog.setActive("BUCHERON", "minecraft:stone", ShopCatalog.Direction.PLAYER_SELLS, true));
    }

    @Test
    void anOfferOfAnotherProfessionCannotBeReachedThroughACompany() {
        catalog.upsertOffer("MAIRIE", "minecraft:paper", ShopCatalog.Direction.PLAYER_BUYS, 1, bd("5"));
        int mairieOfferId = catalog.offersOfProfession("MAIRIE").get(0).id();

        assertTrue(catalog.activeOfferOfCompany(3, mairieOfferId).isPresent());
        assertTrue(catalog.activeOfferOfCompany(1, mairieOfferId).isEmpty(), "un client du bucheron ne doit pas pouvoir viser l'offre de la mairie");
    }

    @Test
    void removeDeletesOnlyTheTargetedOffer() {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("2"));
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_BUYS, 1, bd("4"));

        assertTrue(catalog.removeOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS));
        assertFalse(catalog.removeOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS));

        List<ShopCatalog.Offer> left = catalog.offersOfProfession("BUCHERON");
        assertEquals(1, left.size());
        assertEquals(ShopCatalog.Direction.PLAYER_BUYS, left.get(0).direction());
    }

    @Test
    void floatPricesComeBackRoundedToTwoDecimals() {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("0.1"));
        catalog.upsertOffer("BUCHERON", "minecraft:birch_log", ShopCatalog.Direction.PLAYER_SELLS, 1, bd("12.35"));

        for (ShopCatalog.Offer offer : catalog.offersOfProfession("BUCHERON")) {
            assertEquals(2, Math.min(2, offer.bundlePrice().scale()));
        }
        assertEquals(0, bd("0.10").compareTo(catalog.offersOfProfession("BUCHERON").stream()
                .filter(o -> o.itemKey().equals("minecraft:oak_log")).findFirst().orElseThrow().bundlePrice()));
        assertEquals(0, bd("12.35").compareTo(catalog.offersOfProfession("BUCHERON").stream()
                .filter(o -> o.itemKey().equals("minecraft:birch_log")).findFirst().orElseThrow().bundlePrice()));
    }

    @Test
    void anOfferIsALotWithAnExactPrice() {
        // 32 bois pour 10 : 10 / 32 = 0,3125 ne tient pas dans un prix a 2 decimales, d'ou le prix du LOT.
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 32, bd("10"));

        ShopCatalog.Offer offer = catalog.activeOffersOfCompany(1).get(0);
        assertEquals(32, offer.bundleSize());
        assertEquals(0, bd("10.00").compareTo(offer.bundlePrice()));
    }

    @Test
    void updatingAnOfferReplacesItsLotInsteadOfAddingOne() {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 32, bd("10"));
        assertTrue(catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 16, bd("7.5")));

        List<ShopCatalog.Offer> offers = catalog.offersOfProfession("BUCHERON");
        assertEquals(1, offers.size());
        assertEquals(16, offers.get(0).bundleSize());
        assertEquals(0, bd("7.50").compareTo(offers.get(0).bundlePrice()));
    }

    @Test
    void removingAnOfferAlsoRemovesItsLot() throws SQLException {
        catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 32, bd("10"));
        assertTrue(catalog.removeOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS));

        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM bb_shop_offer_bundles")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void anOfferWithoutLotRowCountsAsOneItemAtItsUnitPrice() throws SQLException {
        // Offre creee par un autre outil, directement dans la table du plugin.
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO bb_shop_offers (profession_code, item_key, direction, unit_price, active) "
                    + "VALUES ('BUCHERON', 'minecraft:stick', 'buy', 0.5, TRUE)");
        }
        ShopCatalog.Offer offer = catalog.offersOfProfession("BUCHERON").get(0);
        assertEquals(1, offer.bundleSize());
        assertEquals(0, bd("0.50").compareTo(offer.bundlePrice()));
    }

    @Test
    void rejectsAnInvalidLotSize() {
        assertThrows(IllegalArgumentException.class, () ->
                catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, 0, bd("1")));
        assertThrows(IllegalArgumentException.class, () ->
                catalog.upsertOffer("BUCHERON", "minecraft:oak_log", ShopCatalog.Direction.PLAYER_SELLS, ShopCatalog.MAX_BUNDLE_SIZE + 1, bd("1")));
    }
}
