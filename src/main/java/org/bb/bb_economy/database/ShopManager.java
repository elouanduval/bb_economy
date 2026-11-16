package org.bb.bb_economy.database;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.bb.bb_economy.item.ShopItems;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Boutiques automatiques : un bloc lie a une entreprise propose les offres de son METIER (table
 * {@code bb_shop_offers}). C'est le compte bancaire de l'entreprise qui paie quand la boutique achete a un
 * joueur et qui encaisse quand elle lui vend.
 *
 * Un echange suit toujours le meme ordre, pour ne jamais dupliquer ni faire apparaitre d'argent ou d'objets :
 * on RETIRE d'abord au joueur ce qu'il donne (objets ou billets, sur le thread principal), on debite/credite
 * le compte en base (thread d'arriere-plan), puis on lui REMET ce qu'il recoit. Si le paiement est refuse, ce
 * qui avait ete retire est rendu.
 */
public final class ShopManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Plafonds de securite pour un seul echange : nombre de lots, et nombre d'objets au total. */
    public static final int MAX_QUANTITY = 1024;
    public static final int MAX_ITEMS = 4096;

    private static final ShopCatalog CATALOG = new ShopCatalog(DatabaseManager::getConnection);

    public enum Payment { CASH, CARD }

    /** Ce que le client affiche : le nom de l'entreprise et ses offres actives. */
    public record ShopView(String companyName, List<ShopCatalog.Offer> offers) {
    }

    public record TradeResult(boolean success, String message) {
        static TradeResult ok(String message) {
            return new TradeResult(true, message);
        }

        static TradeResult fail(String message) {
            return new TradeResult(false, message);
        }
    }

    /** Offre retenue, compte de l'entreprise et total, resolus ensemble en un seul aller-retour base. */
    private record Context(ShopCatalog.Offer offer, String companyAccount, BigDecimal total) {
    }

    private ShopManager() {
    }

    /** Catalogue, pour les commandes d'administration (appels bloquants). */
    public static ShopCatalog catalog() {
        return CATALOG;
    }

    // -------------------------------------------------------------------------
    // Ouverture
    // -------------------------------------------------------------------------

    /** Charge la boutique d'une entreprise hors du thread principal ; vide si l'entreprise n'existe plus. */
    public static void loadShop(ServerPlayer player, int companyId, Consumer<Optional<ShopView>> onResult) {
        DbExecutor.supply(() -> {
            Optional<String> name = BankManager.findCompanyName(companyId);
            if (name.isEmpty()) {
                return Optional.<ShopView>empty();
            }
            return Optional.of(new ShopView(name.get(), CATALOG.activeOffersOfCompany(companyId)));
        }).whenCompleteAsync((result, error) -> {
            if (!BankManager.isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Ouverture de la boutique de l'entreprise {} impossible pour {}",
                        companyId, player.getName().getString(), error);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(result);
        }, player.getServer());
    }

    // -------------------------------------------------------------------------
    // Echange
    // -------------------------------------------------------------------------

    /**
     * Realise un echange. {@code pinValidated} vient de la session de la boutique : le paiement par carte
     * exige un PIN valide ET la carte du joueur en main au moment de l'echange.
     */
    public static void trade(ServerPlayer player, int companyId, int offerId, int quantity, Payment payment,
                             boolean pinValidated, Consumer<TradeResult> onResult) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            onResult.accept(TradeResult.fail("Quantite invalide (de 1 a " + MAX_QUANTITY + " lots)."));
            return;
        }
        if (payment == Payment.CARD && !pinValidated) {
            onResult.accept(TradeResult.fail("Validez d'abord votre PIN pour payer par carte."));
            return;
        }

        // Tout ce qui vient du joueur est capture ici, sur le thread principal.
        UUID uuid = player.getUUID();
        BankManager.HandCards cards = BankManager.cardsInHand(player);
        long inGameDay = BankManager.currentInGameDay(player);

        DbExecutor.supply(() -> {
            Optional<ShopCatalog.Offer> offer = CATALOG.activeOfferOfCompany(companyId, offerId);
            if (offer.isEmpty()) {
                return Optional.<Context>empty();
            }
            String companyAccount = BankManager.ensureCompanyAccount(companyId);
            BigDecimal total = offer.get().bundlePrice().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            return Optional.of(new Context(offer.get(), companyAccount, total));
        }).whenCompleteAsync((context, error) -> {
            if (!BankManager.isStillOnline(player)) return;
            if (error != null) {
                LOGGER.error("Echange en boutique impossible pour {}", player.getName().getString(), error);
                onResult.accept(TradeResult.fail(TxResult.SERVICE_UNAVAILABLE.message()));
                return;
            }
            if (context.isEmpty()) {
                onResult.accept(TradeResult.fail("Cette offre n'est plus disponible."));
                return;
            }
            settle(player, context.get(), quantity, payment, uuid, cards, inGameDay, onResult);
        }, player.getServer());
    }

    /** Suite de l'echange, sur le thread principal : retrait cote joueur, puis paiement en base. */
    private static void settle(ServerPlayer player, Context context, int quantity, Payment payment, UUID uuid,
                               BankManager.HandCards cards, long inGameDay, Consumer<TradeResult> onResult) {
        ShopCatalog.Offer offer = context.offer();
        BigDecimal total = context.total();
        boolean playerSells = offer.direction() == ShopCatalog.Direction.PLAYER_SELLS;

        // La quantite demandee est un nombre de LOTS : on echange lots x taille du lot objets.
        long itemCount = (long) quantity * offer.bundleSize();
        if (itemCount > MAX_ITEMS) {
            onResult.accept(TradeResult.fail("Quantite trop elevee (" + MAX_ITEMS + " objets maximum par echange)."));
            return;
        }
        int items = (int) itemCount;

        Item item = ShopItems.itemOf(offer.itemKey()).orElse(null);
        if (item == null) {
            onResult.accept(TradeResult.fail("Objet inconnu : " + offer.itemKey()));
            return;
        }
        if (!Money.isValid(total)) {
            onResult.accept(TradeResult.fail("Montant invalide."));
            return;
        }
        if (payment == Payment.CASH && !Money.isWhole(total)) {
            onResult.accept(TradeResult.fail("Paiement en liquide impossible : le total ("
                    + total.toPlainString() + " " + Money.CURRENCY + ") n'est pas un montant entier. Payez par carte."));
            return;
        }
        String itemName = new ItemStack(item).getHoverName().getString();
        int bills = payment == Payment.CASH ? Money.toBills(total) : 0;

        // 1) On retire au joueur ce qu'il donne.
        if (playerSells) {
            if (ShopItems.countPlain(player, item) < items) {
                onResult.accept(TradeResult.fail("Vous n'avez pas assez de " + itemName
                        + " (seuls les objets sans donnees ni usure sont acceptes)."));
                return;
            }
            ShopItems.removePlain(player, item, items);
            BankManager.syncInventory(player);
        } else if (payment == Payment.CASH) {
            if (BankManager.countBills(player) < bills) {
                onResult.accept(TradeResult.fail(TxResult.NOT_ENOUGH_CASH.message()));
                return;
            }
            BankManager.removeBills(player, bills);
            BankManager.syncInventory(player);
        }

        // 2) Paiement en base, hors du thread principal.
        DbExecutor.supply(() -> pay(playerSells, payment, context, uuid, cards, inGameDay))
                .whenCompleteAsync((result, error) -> {
                    TxResult outcome = error != null ? BankManager.logAndConvert(error, player, "echange en boutique") : result;
                    if (!BankManager.isStillOnline(player)) {
                        // Le joueur est parti : on ne peut plus lui rendre ni lui remettre quoi que ce soit.
                        LOGGER.warn("Echange en boutique termine apres la deconnexion de {} ({} x{} {}, {}, paiement {}, resultat {})",
                                player.getName().getString(), playerSells ? "vente" : "achat", items,
                                offer.itemKey(), total.toPlainString(), payment, outcome);
                        return;
                    }
                    // 3) On lui remet ce qu'il recoit, ou on lui rend ce qui avait ete retire.
                    if (!outcome.isOk()) {
                        if (playerSells) {
                            ShopItems.give(player, item, items);
                        } else if (payment == Payment.CASH) {
                            BankManager.addMoneyStacks(player, bills);
                        }
                        BankManager.syncInventory(player);
                        onResult.accept(TradeResult.fail(failureMessage(playerSells, outcome)));
                        return;
                    }
                    if (playerSells) {
                        if (payment == Payment.CASH) {
                            BankManager.addMoneyStacks(player, bills);
                        }
                        onResult.accept(TradeResult.ok("Vendu " + items + " x " + itemName + " pour " + total.toPlainString() + " " + Money.CURRENCY + "."));
                    } else {
                        ShopItems.give(player, item, items);
                        onResult.accept(TradeResult.ok("Achete " + items + " x " + itemName + " pour " + total.toPlainString() + " " + Money.CURRENCY + "."));
                    }
                    BankManager.syncInventory(player);
                }, player.getServer());
    }

    /** Mouvement d'argent de l'echange (thread d'arriere-plan : aucun objet Minecraft ici). */
    private static TxResult pay(boolean playerSells, Payment payment, Context context, UUID uuid,
                                BankManager.HandCards cards, long inGameDay) {
        String company = context.companyAccount();
        BigDecimal total = context.total();

        if (payment == Payment.CASH) {
            return playerSells
                    ? BankManager.LEDGER.debitCash(company, total, BankLedger.TYPE_SHOP_SELL_CASH, inGameDay)
                    : BankManager.LEDGER.creditCash(company, total, BankLedger.TYPE_SHOP_BUY_CASH, inGameDay);
        }

        if (!BankManager.ownsAnyOf(uuid, cards)) {
            return TxResult.CARD_NOT_IN_HAND;
        }
        int profileId = BankManager.getActiveProfileId(uuid);
        Optional<String> account = profileId == -1 ? Optional.empty() : BankManager.LEDGER.findPersonalAccountNumber(profileId);
        if (account.isEmpty()) {
            return TxResult.NO_ACCOUNT;
        }
        return playerSells
                ? BankManager.LEDGER.transferBetweenAccounts(company, account.get(), total, BankLedger.TYPE_SHOP_SELL, inGameDay)
                : BankManager.LEDGER.transferBetweenAccounts(account.get(), company, total, BankLedger.TYPE_SHOP_BUY, inGameDay);
    }

    private static String failureMessage(boolean playerSells, TxResult result) {
        if (result == TxResult.INSUFFICIENT_FUNDS) {
            return playerSells ? "La boutique n'a pas assez d'argent pour vous payer." : "Solde insuffisant.";
        }
        return result.message();
    }
}
