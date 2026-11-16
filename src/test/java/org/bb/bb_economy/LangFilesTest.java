package org.bb.bb_economy;

import org.bb.bb_economy.database.TxResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Les fichiers de langue doivent rester complets et coherents : une cle oubliee s'afficherait brute en jeu. */
class LangFilesTest {

    private static final Path LANG_DIR = Path.of("src/main/resources/assets/bb_economy/lang");
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern ENTRY = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,?\\s*$");
    /** Toute chaine qui ressemble a une cle de message du mod (sans le prefixe). */
    private static final Pattern KEY_IN_CODE =
            Pattern.compile("\"((?:gui|pin|tx|atm|tpe|shop|wallet|item)\\.[a-z_]+(?:\\.[a-z_]+)?)\"");

    private static Map<String, String> load(String file) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        for (String line : Files.readAllLines(LANG_DIR.resolve(file), StandardCharsets.UTF_8)) {
            Matcher m = ENTRY.matcher(line);
            if (m.matches()) {
                entries.put(m.group(1), m.group(2));
            }
        }
        return entries;
    }

    private static int placeholders(String text) {
        int count = 0;
        for (int i = text.indexOf("%s"); i >= 0; i = text.indexOf("%s", i + 2)) {
            count++;
        }
        return count;
    }

    @Test
    void frenchAndEnglishHaveTheSameKeysAndPlaceholders() throws IOException {
        Map<String, String> en = load("en_us.json");
        Map<String, String> fr = load("fr_fr.json");

        assertTrue(en.size() > 100, "le fichier anglais semble vide ou mal lu");
        assertEquals(en.keySet(), fr.keySet(), "cles differentes entre en_us et fr_fr");
        for (String key : en.keySet()) {
            assertEquals(placeholders(en.get(key)), placeholders(fr.get(key)), "nombre de %s different pour " + key);
        }
    }

    @Test
    void everyKeyUsedInTheCodeIsTranslated() throws IOException {
        Set<String> en = load("en_us.json").keySet();
        Set<String> used = new TreeSet<>();

        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = KEY_IN_CODE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(Msg.PREFIX + m.group(1));
                }
            }
        }
        for (TxResult result : TxResult.values()) {
            used.add(Msg.PREFIX + "tx." + result.name().toLowerCase());
        }

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(en);
        assertTrue(missing.isEmpty(), "cles absentes des fichiers de langue : " + missing);
        assertTrue(used.size() > 100, "aucune cle trouvee dans le code, la regle de recherche est cassee");
    }

    @Test
    void everyTxResultHasAMessage() throws IOException {
        Set<String> en = load("en_us.json").keySet();
        for (TxResult result : TxResult.values()) {
            assertTrue(en.contains(result.message()), "pas de message pour " + result);
        }
    }
}
