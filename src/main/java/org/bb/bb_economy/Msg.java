package org.bb.bb_economy;

import net.minecraft.network.chat.Component;

/**
 * Messages traduisibles. Le serveur ne connait pas la langue du joueur : il envoie une CLE de traduction (et ses
 * arguments), et c'est le client qui la traduit avec les fichiers de langue du mod (assets/bb_economy/lang).
 *
 * Un message circule sous forme de simple texte : {@code cle} suivie de ses arguments separes par un caractere
 * de controle. Ca permet de garder les paquets et les resultats existants (ils transportent deja des String).
 * Un texte qui ne commence pas par {@link #PREFIX} est affiche tel quel (ex. message d'une exception).
 */
public final class Msg {

    /** Toutes les cles du mod commencent par ceci. */
    public static final String PREFIX = "bb_economy.";

    private static final char SEPARATOR = '\u001F';
    private static final char TRANSLATED_ARG = '\u0002';

    private Msg() {
    }

    /** Encode une cle et ses arguments, a envoyer tel quel au client. */
    public static String of(String key, Object... args) {
        if (args.length == 0) {
            return key;
        }
        StringBuilder out = new StringBuilder(key);
        for (Object arg : args) {
            out.append(SEPARATOR).append(arg);
        }
        return out.toString();
    }

    /** Argument qui sera lui-meme traduit par le client (nom d'un objet, autre message sans argument...). */
    public static String translated(String key) {
        return TRANSLATED_ARG + key;
    }

    /** Texte affichable : traduit si c'est une cle du mod, sinon tel quel. */
    public static Component component(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Component.empty();
        }
        if (!encoded.startsWith(PREFIX)) {
            return Component.literal(encoded);
        }
        String[] parts = encoded.split(String.valueOf(SEPARATOR), -1);
        Object[] args = new Object[parts.length - 1];
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            args[i - 1] = !part.isEmpty() && part.charAt(0) == TRANSLATED_ARG
                    ? Component.translatable(part.substring(1))
                    : part;
        }
        return Component.translatable(parts[0], args);
    }

    /** Texte d'une cle du mod, avec arguments, dans la langue du joueur (cote client uniquement). */
    public static String tr(String key, Object... args) {
        return Component.translatable(PREFIX + key, args).getString();
    }

    /** Idem {@link #component}, en String (cote client uniquement : c'est lui qui connait la langue). */
    public static String text(String encoded) {
        return component(encoded).getString();
    }
}
