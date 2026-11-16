#!/usr/bin/env python3
"""Adds the randomizer page's strings to every language of the in-game/menu texts
(port/android-decomp/app/src/main/res/values*/strings_ingame.xml), appended before </resources>.

The page (RandomizerDialog) is opened from the title screen before the game starts: one seed per run, the
randomizers switched on or off, and the seed files kept, loaded and deleted. Placeholders follow
tools/check_translations.py: %1$s for a name, %1$d for a number, apostrophes escaped, no-break spaces as \\u00A0.

    port/android-decomp/tools/add_randomizer_strings.py [--check]
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# name -> (English, German, Spanish, French, Italian, Portuguese, Russian, Arabic, Hindi, Indonesian, Chinese)
T = {
 "randomizer_title": (
  "Randomizer", "Randomizer", "Aleatorio", "Al\u00e9atoire", "Casuale", "Aleat\u00f3rio",
  "\u0420\u0430\u043d\u0434\u043e\u043c\u0430\u0439\u0437\u0435\u0440",
  "\u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629",
  "\u0930\u0948\u0902\u0921\u092e\u093e\u0907\u091c\u093c\u0930",
  "Acak", "\u968f\u673a\u5316"),
 "randomizer_intro": (
  "One seed defines this run: the same seed and the same switches always give the same game. Switch what you want "
  "randomized, then start the game. The in-game menu shows the seed you are playing.",
  "Ein Seed legt diesen Durchlauf fest: gleicher Seed und gleiche Schalter ergeben immer dasselbe Spiel. Schalte "
  "ein, was zuf\u00e4llig sein soll, und starte dann das Spiel. Das Men\u00fc im Spiel zeigt den Seed, den du "
  "spielst.",
  "Una semilla define esta partida: la misma semilla y los mismos interruptores dan siempre el mismo juego. Activa "
  "lo que quieras aleatorizar y empieza. El men\u00fa del juego muestra la semilla que juegas.",
  "Une graine d\u00e9finit cette partie\u00a0: la m\u00eame graine et les m\u00eames r\u00e9glages donnent toujours "
  "le m\u00eame jeu. Activez ce que vous voulez rendre al\u00e9atoire, puis lancez le jeu. Le menu en jeu affiche "
  "la graine utilis\u00e9e.",
  "Un seme definisce questa partita: stesso seme e stessi interruttori danno sempre lo stesso gioco. Attiva ci\u00f2 "
  "che vuoi rendere casuale, poi avvia. Il menu in gioco mostra il seme che stai giocando.",
  "Uma semente define esta partida: a mesma semente e os mesmos interruptores d\u00e3o sempre o mesmo jogo. Ativa "
  "o que quiseres aleatorizar e come\u00e7a. O menu do jogo mostra a semente que est\u00e1s a jogar.",
  "\u041e\u0434\u0438\u043d seed \u043e\u043f\u0440\u0435\u0434\u0435\u043b\u044f\u0435\u0442 \u044d\u0442\u043e "
  "\u043f\u0440\u043e\u0445\u043e\u0436\u0434\u0435\u043d\u0438\u0435: \u0442\u043e\u0442 \u0436\u0435 seed \u0438 "
  "\u0442\u0435 \u0436\u0435 \u043f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0430\u0442\u0435\u043b\u0438 "
  "\u0432\u0441\u0435\u0433\u0434\u0430 \u0434\u0430\u044e\u0442 \u0442\u0443 \u0436\u0435 \u0438\u0433\u0440\u0443. "
  "\u0412\u043a\u043b\u044e\u0447\u0438\u0442\u0435, \u0447\u0442\u043e \u0445\u043e\u0442\u0438\u0442\u0435 "
  "\u0441\u043b\u0443\u0447\u0430\u0439\u043d\u044b\u043c, \u0438 \u043d\u0430\u0447\u043d\u0438\u0442\u0435 "
  "\u0438\u0433\u0440\u0443. \u041c\u0435\u043d\u044e \u0432 \u0438\u0433\u0440\u0435 \u043f\u043e\u043a\u0430\u0437\u044b\u0432\u0430\u0435\u0442 "
  "\u0442\u0435\u043a\u0443\u0449\u0438\u0439 seed.",
  "\u0628\u0630\u0631\u0629 \u0648\u0627\u062d\u062f\u0629 \u062a\u062d\u062f\u062f \u0647\u0630\u0647 "
  "\u0627\u0644\u062c\u0648\u0644\u0629: \u0646\u0641\u0633 \u0627\u0644\u0628\u0630\u0631\u0629 "
  "\u0648\u0646\u0641\u0633 \u0627\u0644\u0645\u0641\u0627\u062a\u064a\u062d \u062a\u0639\u0637\u064a\u0627\u0646 "
  "\u062f\u0627\u0626\u0645\u0627\u064b \u0646\u0641\u0633 \u0627\u0644\u0644\u0639\u0628\u0629. "
  "\u0641\u0639\u0651\u0644 \u0645\u0627 \u062a\u0631\u064a\u062f\u0647 \u0639\u0634\u0648\u0627\u0626\u064a\u0627\u064b "
  "\u062b\u0645 \u0627\u0628\u062f\u0623 \u0627\u0644\u0644\u0639\u0628\u0629. \u0642\u0627\u0626\u0645\u0629 "
  "\u0627\u0644\u0644\u0639\u0628\u0629 \u062a\u0639\u0631\u0636 \u0627\u0644\u0628\u0630\u0631\u0629 "
  "\u0627\u0644\u062d\u0627\u0644\u064a\u0629.",
  "\u090f\u0915 \u0938\u0940\u0921 \u0907\u0938 \u0916\u0947\u0932 \u0915\u094b \u0924\u092f "
  "\u0915\u0930\u0924\u093e \u0939\u0948: \u0935\u0939\u0940 \u0938\u0940\u0921 \u0914\u0930 "
  "\u0935\u0939\u0940 \u0938\u094d\u0935\u093f\u091a \u0939\u092e\u0947\u0936\u093e \u0935\u0939\u0940 "
  "\u0916\u0947\u0932 \u0926\u0947\u0924\u0947 \u0939\u0948\u0902\u0964 \u091c\u094b "
  "\u092f\u093e\u0926\u0943\u091a\u094d\u091b\u093f\u0915 \u091a\u093e\u0939\u093f\u090f \u0935\u0939 "
  "\u091a\u093e\u0932\u0942 \u0915\u0930\u0947\u0902, \u092b\u093f\u0930 \u0916\u0947\u0932 "
  "\u0936\u0941\u0930\u0942 \u0915\u0930\u0947\u0902\u0964 \u0916\u0947\u0932 \u0915\u093e "
  "\u092e\u0947\u0928\u0942 \u0935\u0930\u094d\u0924\u092e\u093e\u0928 \u0938\u0940\u0921 "
  "\u0926\u093f\u0916\u093e\u0924\u093e \u0939\u0948\u0964",
  "Satu benih menentukan permainan ini: benih dan sakelar yang sama selalu memberi permainan yang sama. Nyalakan "
  "apa yang ingin diacak, lalu mulai. Menu dalam permainan menunjukkan benih yang sedang dimainkan.",
  "\u4e00\u4e2a\u79cd\u5b50\u51b3\u5b9a\u8fd9\u4e00\u8f6e\uff1a\u76f8\u540c\u7684\u79cd\u5b50\u548c\u76f8\u540c"
  "\u7684\u5f00\u5173\u603b\u4f1a\u5f97\u5230\u76f8\u540c\u7684\u6e38\u620f\u3002\u6253\u5f00\u4f60\u60f3"
  "\u968f\u673a\u7684\u5185\u5bb9\uff0c\u7136\u540e\u5f00\u59cb\u6e38\u620f\u3002\u6e38\u620f\u5185\u83dc"
  "\u5355\u4f1a\u663e\u793a\u5f53\u524d\u4f7f\u7528\u7684\u79cd\u5b50\u3002"),
 "randomizer_new_seed": (
  "New seed", "Neuer Seed", "Nueva semilla", "Nouvelle graine", "Nuovo seme", "Nova semente",
  "\u041d\u043e\u0432\u044b\u0439 seed", "\u0628\u0630\u0631\u0629 \u062c\u062f\u064a\u062f\u0629",
  "\u0928\u092f\u093e \u0938\u0940\u0921", "Benih baru", "\u65b0\u79cd\u5b50"),
 "randomizer_seed": (
  "Seed %1$d", "Seed %1$d", "Semilla %1$d", "Graine %1$d", "Seme %1$d", "Semente %1$d",
  "Seed %1$d", "\u0627\u0644\u0628\u0630\u0631\u0629 %1$d", "\u0938\u0940\u0921 %1$d", "Benih %1$d",
  "\u79cd\u5b50 %1$d"),
 "randomizer_no_seed": (
  "No seed yet", "Noch kein Seed", "A\u00fan sin semilla", "Pas encore de graine", "Nessun seme", "Ainda sem semente",
  "\u041f\u043e\u043a\u0430 \u043d\u0435\u0442 seed", "\u0644\u0627 \u062a\u0648\u062c\u062f \u0628\u0630\u0631\u0629 \u0628\u0639\u062f",
  "\u0905\u092d\u0940 \u0915\u094b\u0908 \u0938\u0940\u0921 \u0928\u0939\u0940\u0902", "Belum ada benih",
  "\u5c1a\u65e0\u79cd\u5b50"),
 "randomizer_features": (
  "Randomizers", "Randomizer", "Aleatorizadores", "Options al\u00e9atoires", "Casualizzatori", "Aleatorizadores",
  "\u0420\u0430\u043d\u0434\u043e\u043c\u0430\u0439\u0437\u0435\u0440\u044b", "\u0627\u0644\u0639\u0646\u0627\u0635\u0631 \u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629",
  "\u0930\u0948\u0902\u0921\u092e\u093e\u0907\u091c\u093c\u0930", "Pengacak", "\u968f\u673a\u9879"),
 "randomizer_saved_seeds": (
  "Saved seeds", "Gespeicherte Seeds", "Semillas guardadas", "Graines enregistr\u00e9es", "Semi salvati",
  "Sementes guardadas", "\u0421\u043e\u0445\u0440\u0430\u043d\u0451\u043d\u043d\u044b\u0435 seed",
  "\u0627\u0644\u0628\u0630\u0648\u0631 \u0627\u0644\u0645\u062d\u0641\u0648\u0638\u0629",
  "\u0938\u0939\u0947\u091c\u0947 \u0917\u090f \u0938\u0940\u0921", "Benih tersimpan", "\u5df2\u4fdd\u5b58\u7684\u79cd\u5b50"),
 "randomizer_no_files": (
  "No saved seeds yet.", "Noch keine gespeicherten Seeds.", "A\u00fan no hay semillas guardadas.",
  "Aucune graine enregistr\u00e9e.", "Nessun seme salvato.", "Ainda n\u00e3o h\u00e1 sementes guardadas.",
  "\u0421\u043e\u0445\u0440\u0430\u043d\u0451\u043d\u043d\u044b\u0445 seed \u043f\u043e\u043a\u0430 \u043d\u0435\u0442.",
  "\u0644\u0627 \u062a\u0648\u062c\u062f \u0628\u0630\u0648\u0631 \u0645\u062d\u0641\u0648\u0638\u0629 \u0628\u0639\u062f.",
  "\u0905\u092d\u0940 \u0915\u094b\u0908 \u0938\u0939\u0947\u091c\u0940 \u0938\u0940\u0921 \u0928\u0939\u0940\u0902\u0964",
  "Belum ada benih tersimpan.", "\u5c1a\u65e0\u5df2\u4fdd\u5b58\u7684\u79cd\u5b50\u3002"),
 "randomizer_name_hint": (
  "Name for this seed", "Name f\u00fcr diesen Seed", "Nombre de esta semilla", "Nom de cette graine",
  "Nome di questo seme", "Nome desta semente", "\u0418\u043c\u044f \u044d\u0442\u043e\u0433\u043e seed",
  "\u0627\u0633\u0645 \u0647\u0630\u0647 \u0627\u0644\u0628\u0630\u0631\u0629", "\u0907\u0938 \u0938\u0940\u0921 \u0915\u093e \u0928\u093e\u092e",
  "Nama benih ini", "\u6b64\u79cd\u5b50\u7684\u540d\u79f0"),
 "randomizer_save": (
  "Save", "Speichern", "Guardar", "Enregistrer", "Salva", "Guardar",
  "\u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c", "\u062d\u0641\u0638", "\u0938\u0939\u0947\u091c\u0947\u0902",
  "Simpan", "\u4fdd\u5b58"),
 "randomizer_load": (
  "Load", "Laden", "Cargar", "Charger", "Carica", "Carregar",
  "\u0417\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u044c", "\u062a\u062d\u0645\u064a\u0644", "\u0932\u094b\u0921",
  "Muat", "\u52a0\u8f7d"),
 "randomizer_delete": (
  "Delete", "L\u00f6schen", "Borrar", "Supprimer", "Elimina", "Eliminar",
  "\u0423\u0434\u0430\u043b\u0438\u0442\u044c", "\u062d\u0630\u0641", "\u0939\u091f\u093e\u090f\u0902",
  "Hapus", "\u5220\u9664"),
 "randomizer_close": (
  "Close", "Schlie\u00dfen", "Cerrar", "Fermer", "Chiudi", "Fechar",
  "\u0417\u0430\u043a\u0440\u044b\u0442\u044c", "\u0625\u063a\u0644\u0627\u0642", "\u092c\u0902\u0926 \u0915\u0930\u0947\u0902",
  "Tutup", "\u5173\u95ed"),
 "randomizer_saved": (
  "Saved as %1$s", "Gespeichert als %1$s", "Guardada como %1$s", "Enregistr\u00e9e sous %1$s",
  "Salvato come %1$s", "Guardada como %1$s", "\u0421\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u043e \u043a\u0430\u043a %1$s",
  "\u062d\u0641\u0638\u062a \u0643\u0640 %1$s", "%1$s \u0915\u0947 \u0930\u0942\u092a \u092e\u0947\u0902 \u0938\u0939\u0947\u091c\u093e",
  "Disimpan sebagai %1$s", "\u5df2\u4fdd\u5b58\u4e3a %1$s"),
 "randomizer_loaded": (
  "Loaded %1$s", "Geladen: %1$s", "Cargada %1$s", "Charg\u00e9e\u00a0: %1$s", "Caricato %1$s",
  "Carregada %1$s", "\u0417\u0430\u0433\u0440\u0443\u0436\u0435\u043d\u043e %1$s",
  "\u062a\u0645 \u062a\u062d\u0645\u064a\u0644 %1$s", "%1$s \u0932\u094b\u0921 \u0939\u0941\u0906",
  "Dimuat %1$s", "\u5df2\u52a0\u8f7d %1$s"),
 "randomizer_deleted": (
  "Deleted %1$s", "Gel\u00f6scht: %1$s", "Eliminada %1$s", "Supprim\u00e9e\u00a0: %1$s", "Eliminato %1$s",
  "Eliminada %1$s", "\u0423\u0434\u0430\u043b\u0435\u043d\u043e %1$s",
  "\u062a\u0645 \u062d\u0630\u0641 %1$s", "%1$s \u0939\u091f\u093e\u092f\u093e \u0917\u092f\u093e",
  "Dihapus %1$s", "\u5df2\u5220\u9664 %1$s"),
 "randomizer_none_on": (
  "No randomizer is on. Switch one on to start a randomized run.",
  "Kein Randomizer ist an. Schalte einen ein, um einen zuf\u00e4lligen Durchlauf zu starten.",
  "Ning\u00fan aleatorizador est\u00e1 activo. Activa uno para empezar una partida aleatoria.",
  "Aucun mode al\u00e9atoire n\u2019est actif. Activez-en un pour lancer une partie al\u00e9atoire.",
  "Nessun casualizzatore \u00e8 attivo. Attivane uno per iniziare una partita casuale.",
  "Nenhum aleatorizador est\u00e1 ligado. Liga um para come\u00e7ar uma partida aleat\u00f3ria.",
  "\u041d\u0438 \u043e\u0434\u0438\u043d \u0440\u0430\u043d\u0434\u043e\u043c\u0430\u0439\u0437\u0435\u0440 \u043d\u0435 "
  "\u0432\u043a\u043b\u044e\u0447\u0451\u043d. \u0412\u043a\u043b\u044e\u0447\u0438\u0442\u0435 \u043e\u0434\u0438\u043d, "
  "\u0447\u0442\u043e\u0431\u044b \u043d\u0430\u0447\u0430\u0442\u044c \u0441\u043b\u0443\u0447\u0430\u0439\u043d\u043e\u0435 "
  "\u043f\u0440\u043e\u0445\u043e\u0436\u0434\u0435\u043d\u0438\u0435.",
  "\u0644\u0627 \u064a\u0648\u062c\u062f \u0639\u0646\u0635\u0631 \u0639\u0634\u0648\u0627\u0626\u064a "
  "\u0645\u0641\u0639\u0651\u0644. \u0641\u0639\u0651\u0644 \u0648\u0627\u062d\u062f\u0627\u064b "
  "\u0644\u0628\u062f\u0621 \u062c\u0648\u0644\u0629 \u0639\u0634\u0648\u0627\u0626\u064a\u0629.",
  "\u0915\u094b\u0908 \u0930\u0948\u0902\u0921\u092e\u093e\u0907\u091c\u093c\u0930 \u091a\u093e\u0932\u0942 "
  "\u0928\u0939\u0940\u0902 \u0939\u0948\u0964 \u0930\u0948\u0902\u0921\u092e \u0916\u0947\u0932 "
  "\u0936\u0941\u0930\u0942 \u0915\u0930\u0928\u0947 \u0915\u0947 \u0932\u093f\u090f \u090f\u0915 "
  "\u091a\u093e\u0932\u0942 \u0915\u0930\u0947\u0902\u0964",
  "Tiada pengacak yang dihidupkan. Hidupkan satu untuk memulakan permainan acak.",
  "\u6ca1\u6709\u5f00\u542f\u4efb\u4f55\u968f\u673a\u9879\u3002\u6253\u5f00\u4e00\u9879\u4ee5\u5f00\u59cb"
  "\u968f\u673a\u6e38\u620f\u3002"),
}

LANGS = ["values", "values-de", "values-es", "values-fr", "values-it", "values-pt",
         "values-ru", "values-ar", "values-hi", "values-in", "values-zh"]


def escape(text):
    text = text.replace("\u00a0", "\\u00A0")
    text = text.replace("'", "\\'")
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    check = "--check" in sys.argv
    changed = 0
    for index, lang in enumerate(LANGS):
        path = os.path.join(RES, lang, "strings_ingame.xml")
        if not os.path.exists(path):
            print("%s: no file" % lang)
            continue
        text = open(path, encoding="utf-8").read()
        add = []
        replaced = 0
        for name, translations in T.items():
            value = escape(translations[index])
            entry = '    <string name="%s">%s</string>' % (name, value)
            if 'name="%s"' % name in text:
                # replace in place so a fixed text lands on a second run too
                out = []
                for line in text.split("\n"):
                    out.append(entry if ('name="%s"' % name) in line else line)
                text = "\n".join(out)
                replaced += 1
            else:
                add.append(entry)
        if add:
            if "</resources>" not in text:
                print("%s: no </resources>" % lang)
                continue
            text = text.replace("</resources>", "\n".join(add) + "\n</resources>")
        if not check:
            open(path, "w", encoding="utf-8").write(text)
        changed += 1
        print("%s: %s (%d new, %d replaced)" % (lang, "would write" if check else "written", len(add), replaced))
    print("%d language(s)" % changed)


if __name__ == "__main__":
    main()
