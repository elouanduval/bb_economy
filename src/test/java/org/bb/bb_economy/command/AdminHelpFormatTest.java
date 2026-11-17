package org.bb.bb_economy.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminHelpFormatTest {

    @Test
    void sectionTitleLosesItsDashesAndIsGold() {
        MutableComponent title = AdminCommands.formatHelpLine("--- Entreprises (nom entre guillemets) ---");

        assertEquals("\u25B6 Entreprises (nom entre guillemets)", title.getString());
        assertEquals(ChatFormatting.GOLD.getColor(), title.getStyle().getColor().getValue());
    }

    @Test
    void commandKeepsItsTextAndColorsItsArguments() {
        MutableComponent line = AdminCommands.formatHelpLine("/bb_economy bank history <joueur> [nombre] - Dernieres transactions");

        assertEquals(" /bb_economy bank history <joueur> [nombre] - Dernieres transactions", line.getString());

        // /bb_economy : jaune ; <joueur> : cyan ; [nombre] : cyan fonce
        Component command = line.getSiblings().get(0);
        assertEquals(ChatFormatting.YELLOW.getColor(), command.getSiblings().get(0).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.AQUA.getColor(), command.getSiblings().get(3).getStyle().getColor().getValue());
        assertEquals(ChatFormatting.DARK_AQUA.getColor(), command.getSiblings().get(4).getStyle().getColor().getValue());
    }

    @Test
    void clickingACommandSuggestsItUpToItsFirstArgument() {
        MutableComponent line = AdminCommands.formatHelpLine("/bb_economy bank create <joueur> - Cree un compte");

        ClickEvent click = line.getSiblings().get(0).getStyle().getClickEvent();
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.getAction());
        assertEquals("/bb_economy bank create ", click.getValue());
    }

    @Test
    void aLineWithoutDescriptionStillWorks() {
        MutableComponent line = AdminCommands.formatHelpLine("/bb_economy company open_all_accounts");
        assertTrue(line.getString().contains("open_all_accounts"));
    }
}
