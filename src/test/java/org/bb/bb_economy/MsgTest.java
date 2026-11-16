package org.bb.bb_economy;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MsgTest {

    @Test
    void aKeyWithoutArgumentsIsSentAsIs() {
        assertEquals("bb_economy.tx.ok", Msg.of("bb_economy.tx.ok"));
    }

    @Test
    void keyAndArgumentsSurviveTheTrip() {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                Msg.component(Msg.of("bb_economy.shop.sold", 32, "oak", "10 bb_euros")).getContents());

        assertEquals("bb_economy.shop.sold", contents.getKey());
        assertEquals(3, contents.getArgs().length);
        assertEquals("32", contents.getArgs()[0]);
        assertEquals("10 bb_euros", contents.getArgs()[2]);
    }

    @Test
    void aTranslatedArgumentBecomesATranslatableComponent() {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                Msg.component(Msg.of("bb_economy.tpe.refused", Msg.translated("bb_economy.tx.insufficient_funds"))).getContents());

        Component nested = assertInstanceOf(Component.class, contents.getArgs()[0]);
        assertEquals("bb_economy.tx.insufficient_funds", assertInstanceOf(TranslatableContents.class, nested.getContents()).getKey());
    }

    @Test
    void anEmptyArgumentIsKept() {
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class,
                Msg.component(Msg.of("bb_economy.shop.sold", "", "x")).getContents());
        assertEquals(2, contents.getArgs().length);
        assertEquals("", contents.getArgs()[0]);
    }

    @Test
    void textThatIsNotAModKeyIsShownAsIs() {
        LiteralContents contents = assertInstanceOf(LiteralContents.class, Msg.component("Erreur base de donnees : timeout").getContents());
        assertEquals("Erreur base de donnees : timeout", contents.text());
        assertTrue(Msg.component("").getString().isEmpty());
        assertTrue(Msg.component(null).getString().isEmpty());
    }
}
