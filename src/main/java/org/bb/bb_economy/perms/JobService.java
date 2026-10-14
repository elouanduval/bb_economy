package org.bb.bb_economy.perms;

import com.mojang.logging.LogUtils;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.UUID;

/**
 * Pont vers LuckPerms : une entreprise est un groupe LuckPerms, et un salarie en est simplement membre.
 * bb_economy ne gere plus aucune donnee de metier (ni salaire, ni rattachement) : tout ca vit dans LuckPerms,
 * bb_economy ne fait que verifier l'appartenance a un groupe pour autoriser l'usage d'un TPE.
 *
 * LuckPerms est une dependance optionnelle (mods.toml) : sans elle, aucun joueur n'est considere comme salarie.
 * Toute methode de cette classe ne touche aux types LuckPerms qu'apres avoir verifie {@link #isAvailable()}.
 */
public final class JobService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile LuckPerms api;

    private JobService() {
    }

    public static void init() {
        if (!ModList.get().isLoaded("luckperms")) {
            LOGGER.warn("LuckPerms n'est pas installe : aucun joueur ne sera reconnu comme salarie d'une entreprise.");
            api = null;
            return;
        }
        try {
            api = LuckPermsProvider.get();
            LOGGER.info("LuckPerms detecte : la gestion des entreprises est active.");
        } catch (IllegalStateException e) {
            LOGGER.error("LuckPerms est installe mais son API n'est pas encore prete.", e);
            api = null;
        }
    }

    public static boolean isAvailable() {
        return api != null;
    }

    /**
     * Vrai si le joueur est membre du groupe LuckPerms de cette entreprise, c'est-a-dire salarie.
     * Le nom du groupe doit correspondre exactement (insensible a la casse) au company_id de bb_companies.
     */
    public static boolean isMemberOfCompany(UUID playerUuid, String companyId) {
        LuckPerms current = api;
        if (current == null || companyId == null || companyId.isBlank()) {
            return false;
        }
        User user = current.getUserManager().getUser(playerUuid);
        if (user == null) {
            // Le joueur n'est pas (ou plus) charge par LuckPerms : par prudence, on refuse plutot que de deviner.
            return false;
        }
        // Convention LuckPerms : etre membre du groupe "x" accorde automatiquement la permission "group.x".
        String node = "group." + companyId.toLowerCase(Locale.ROOT);
        return user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
    }
}
