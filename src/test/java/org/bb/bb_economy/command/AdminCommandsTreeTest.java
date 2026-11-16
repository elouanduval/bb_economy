package org.bb.bb_economy.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Construit l'arbre de commandes sans serveur et verifie sa structure : une erreur d'arbre (verbe ou argument
 * mal imbrique) ne se verrait sinon qu'au lancement du serveur.
 */
class AdminCommandsTreeTest {

    @Test
    void everyDocumentedAdminCommandExistsInTheTree() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        AdminCommands.register(dispatcher);

        Set<String> usages = new TreeSet<>(Arrays.asList(
                dispatcher.getAllUsage(dispatcher.getRoot(), null, false)));

        Set<String> expected = new TreeSet<>(Set.of(
                "bb_economy help",
                "bb_economy bank create <player>",
                "bb_economy bank reissue_card <player>",
                "bb_economy bank unlock <player>",
                "bb_economy bank info <player>",
                "bb_economy bank credit <player> <amount>",
                "bb_economy bank debit <player> <amount>",
                "bb_economy bank history <player>",
                "bb_economy bank history <player> <count>",
                "bb_economy bank freeze <player>",
                "bb_economy bank unfreeze <player>",
                "bb_economy company open_account <company>",
                "bb_economy company open_all_accounts",
                "bb_economy company info <company>",
                "bb_economy company credit <company> <amount>",
                "bb_economy company debit <company> <amount>",
                "bb_economy company history <company>",
                "bb_economy company history <company> <count>",
                "bb_economy company freeze <company>",
                "bb_economy company unfreeze <company>",
                "bb_economy card regive <player>",
                "bb_economy give tpe <company>",
                "bb_economy give tpe <company> <player>"
        ));

        assertEquals(expected, usages);
    }
}
