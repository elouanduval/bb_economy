package org.bb.bb_economy.database;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Pool de threads dedie aux appels bloquants vers MariaDB (HikariCP/JDBC sont synchrones).
 *
 * Rien de ce qui s'execute ici ne doit toucher a un Player, un ItemStack, un inventaire ou un etat de
 * bloc/menu : ce sont des objets du thread principal du serveur, jamais thread-safe. Le resultat doit
 * toujours etre rapporte sur le thread principal (via MinecraftServer#execute) avant de toucher au jeu.
 * Voir les methodes *Async de BankManager pour les points d'entree deja cables correctement.
 */
public final class DbExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 4 threads : le pool HikariCP plafonne deja a 10 connexions, ca suffit largement pour un serveur RP.
    // Le but n'est pas le parallelisme mais de ne jamais faire attendre le tick sur un aller-retour SQL.
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "bb-economy-db-worker");
        thread.setDaemon(true);
        return thread;
    });

    private DbExecutor() {
    }

    public static <T> CompletableFuture<T> supply(Supplier<T> blockingWork) {
        return CompletableFuture.supplyAsync(blockingWork, EXECUTOR);
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
        LOGGER.info("Pool de threads DB arrete.");
    }
}
