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
 "randomizer_chance": (
  "Merchants with new goods: %1$d%%", "H\u00e4ndler mit neuer Ware: %1$d%%",
  "Mercaderes con mercanc\u00eda nueva: %1$d%%", "Marchands avec de la nouveaut\u00e9\u00a0: %1$d%%",
  "Mercanti con merce nuova: %1$d%%", "Mercadores com mercadoria nova: %1$d%%",
  "\u0422\u043e\u0440\u0433\u043e\u0432\u0446\u0435\u0432 \u0441 \u043d\u043e\u0432\u044b\u043c \u0442\u043e\u0432\u0430\u0440\u043e\u043c: %1$d%%",
  "\u0627\u0644\u062a\u062c\u0627\u0631 \u0630\u0648\u0648 \u0627\u0644\u0628\u0636\u0627\u0639\u0629 \u0627\u0644\u062c\u062f\u064a\u062f\u0629: %1$d%%",
  "\u0928\u0908 \u0935\u0938\u094d\u0924\u0941 \u0935\u093e\u0932\u0947 \u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940: %1$d%%",
  "Pedagang dengan barang baharu: %1$d%%", "\u6709\u65b0\u8d27\u7684\u5546\u4eba\uff1a%1$d%%"),
 "randomizer_weapons": (
  "Weapons: %1$d chosen", "Waffen: %1$d gew\u00e4hlt", "Armas: %1$d elegidas", "Armes\u00a0: %1$d choisies",
  "Armi: %1$d scelte", "Armas: %1$d escolhidas",
  "\u041e\u0440\u0443\u0436\u0438\u0435: \u0432\u044b\u0431\u0440\u0430\u043d\u043e %1$d",
  "\u0627\u0644\u0623\u0633\u0644\u062d\u0629: %1$d \u0645\u062e\u062a\u0627\u0631\u0629",
  "\u0939\u0925\u093f\u092f\u093e\u0930: %1$d \u091a\u0941\u0928\u0947 \u0917\u090f",
  "Senjata: %1$d dipilih", "\u6b66\u5668\uff1a\u5df2\u9009 %1$d"),
 "randomizer_weapons_title": (
  "Weapons the merchant may sell", "Waffen, die der H\u00e4ndler verkaufen darf",
  "Armas que el mercader puede vender", "Armes que le marchand peut vendre",
  "Armi che il mercante pu\u00f2 vendere", "Armas que o mercador pode vender",
  "\u041e\u0440\u0443\u0436\u0438\u0435, \u043a\u043e\u0442\u043e\u0440\u043e\u0435 \u043c\u043e\u0436\u0435\u0442 "
  "\u043f\u0440\u043e\u0434\u0430\u0432\u0430\u0442\u044c \u0442\u043e\u0440\u0433\u043e\u0432\u0435\u0446",
  "\u0627\u0644\u0623\u0633\u0644\u062d\u0629 \u0627\u0644\u062a\u064a \u064a\u0645\u0643\u0646 "
  "\u0644\u0644\u062a\u0627\u062c\u0631 \u0628\u064a\u0639\u0647\u0627",
  "\u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940 \u0915\u094c\u0928 \u0938\u0947 \u0939\u0925\u093f\u092f\u093e\u0930 "
  "\u092c\u0947\u091a \u0938\u0915\u0924\u093e \u0939\u0948",
  "Senjata yang boleh dijual pedagang", "\u5546\u4eba\u53ef\u4ee5\u51fa\u552e\u7684\u6b66\u5668"),
 "randomizer_ok": (
  "OK", "OK", "Aceptar", "OK", "OK", "OK", "OK", "\u0645\u0648\u0627\u0641\u0642",
  "\u0920\u0940\u0915 \u0939\u0948", "OK", "\u786e\u5b9a"),
 "randomizer_cancel": (
  "Cancel", "Abbrechen", "Cancelar", "Annuler", "Annulla", "Cancelar",
  "\u041e\u0442\u043c\u0435\u043d\u0430", "\u0625\u0644\u063a\u0627\u0621", "\u0930\u0926\u094d\u0926 \u0915\u0930\u0947\u0902",
  "Batal", "\u53d6\u6d88"),
 "randomizer_load_title": (
  "Seed of this save", "Seed dieses Spielstands", "Semilla de esta partida",
  "Graine de cette sauvegarde", "Seme di questo salvataggio", "Semente deste jogo guardado",
  "Seed \u044d\u0442\u043e\u0433\u043e \u0441\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u0438\u044f",
  "\u0628\u0630\u0631\u0629 \u0647\u0630\u0627 \u0627\u0644\u062d\u0641\u0638",
  "\u0907\u0938 \u0938\u0947\u0935 \u0915\u093e \u0938\u0940\u0921",
  "Benih simpanan ini", "\u6b64\u5b58\u6863\u7684\u79cd\u5b50"),
 "randomizer_load_question": (
  "This save was made with seed %1$d. Switch to that seed so you play the same run again?",
  "Dieser Spielstand wurde mit Seed %1$d erstellt. Auf diesen Seed wechseln, damit du denselben Durchlauf "
  "spielst?",
  "\u00bfEsta partida se cre\u00f3 con la semilla %1$d. Cambiar a esa semilla para jugar la misma partida?",
  "Cette sauvegarde a \u00e9t\u00e9 cr\u00e9\u00e9e avec la graine %1$d. Passer \u00e0 cette graine pour "
  "rejouer la m\u00eame partie\u00a0?",
  "Questo salvataggio \u00e8 stato creato con il seme %1$d. Passare a quel seme per rigiocare la stessa partita?",
  "Este jogo foi criado com a semente %1$d. Mudar para essa semente para jogar a mesma partida?",
  "\u042d\u0442\u043e \u0441\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u0438\u0435 \u0441\u043e\u0437\u0434\u0430\u043d\u043e "
  "\u0441 seed %1$d. \u041f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0438\u0442\u044c\u0441\u044f \u043d\u0430 "
  "\u044d\u0442\u043e\u0442 seed, \u0447\u0442\u043e\u0431\u044b \u0438\u0433\u0440\u0430\u0442\u044c \u0442\u043e "
  "\u0436\u0435 \u043f\u0440\u043e\u0445\u043e\u0436\u0434\u0435\u043d\u0438\u0435?",
  "\u062d\u064f\u0641\u0638\u062a \u0647\u0630\u0647 \u0627\u0644\u0644\u0639\u0628\u0629 "
  "\u0628\u0627\u0644\u0628\u0630\u0631\u0629 %1$d. \u0627\u0644\u062a\u0628\u062f\u064a\u0644 "
  "\u0625\u0644\u0649 \u062a\u0644\u0643 \u0627\u0644\u0628\u0630\u0631\u0629 \u0644\u0644\u0639\u0628 "
  "\u0646\u0641\u0633 \u0627\u0644\u062c\u0648\u0644\u0629\u061f",
  "\u092f\u0939 \u0938\u0947\u0935 \u0938\u0940\u0921 %1$d \u0938\u0947 \u092c\u0928\u093e\u092f\u093e "
  "\u0917\u092f\u093e \u0925\u093e\u0964 \u0935\u0939\u0940 \u0916\u0947\u0932 \u0926\u094b\u092c\u093e\u0930\u093e "
  "\u0916\u0947\u0932\u0928\u0947 \u0915\u0947 \u0932\u093f\u090f \u0909\u0938 \u0938\u0940\u0921 "
  "\u092a\u0930 \u0938\u094d\u0935\u093f\u091a \u0915\u0930\u0947\u0902?",
  "Simpanan ini dibuat dengan benih %1$d. Tukar kepada benih itu untuk memainkan permainan yang sama?",
  "\u6b64\u5b58\u6863\u4f7f\u7528\u79cd\u5b50 %1$d \u521b\u5efa\u3002\u662f\u5426\u5207\u6362\u5230"
  "\u8be5\u79cd\u5b50\u4ee5\u91cd\u73a9\u540c\u4e00\u8f6e\uff1f"),
 "randomizer_load_switch": (
  "Switch", "Wechseln", "Cambiar", "Changer", "Passa", "Mudar",
  "\u041f\u0435\u0440\u0435\u043a\u043b\u044e\u0447\u0438\u0442\u044c", "\u062a\u0628\u062f\u064a\u0644",
  "\u0938\u094d\u0935\u093f\u091a \u0915\u0930\u0947\u0902", "Tukar", "\u5207\u6362"),
 "randomizer_load_keep": (
  "Keep current", "Aktuellen behalten", "Mantener actual", "Garder l\u2019actuelle", "Tieni attuale",
  "Manter atual", "\u041e\u0441\u0442\u0430\u0432\u0438\u0442\u044c \u0442\u0435\u043a\u0443\u0449\u0438\u0439",
  "\u0627\u0644\u0625\u0628\u0642\u0627\u0621 \u0639\u0644\u0649 \u0627\u0644\u062d\u0627\u0644\u064a",
  "\u0935\u0930\u094d\u0924\u092e\u093e\u0928 \u0930\u0916\u0947\u0902", "Kekalkan semasa", "\u4fdd\u6301\u5f53\u524d"),
 "ingame_seed_active": (
  "Seed %1$d \\u00b7 %2$d on", "Seed %1$d \\u00b7 %2$d an", "Semilla %1$d \\u00b7 %2$d act.", "Graine %1$d \\u00b7 %2$d actifs",
  "Seme %1$d \\u00b7 %2$d att.", "Semente %1$d \\u00b7 %2$d lig.", "Seed %1$d \\u00b7 %2$d \u0432\u043a\u043b.",
  "\u0627\u0644\u0628\u0630\u0631\u0629 %1$d \\u00b7 %2$d \u0645\u0641\u0639\u0651\u0644",
  "\u0938\u0940\u0921 %1$d \\u00b7 %2$d \u091a\u093e\u0932\u0942", "Benih %1$d \\u00b7 %2$d hidup",
  "\u79cd\u5b50 %1$d \\u00b7 %2$d \u5f00\u542f"),
 "ingame_seed_none": (
  "No seed: this is a plain game.", "Kein Seed: ein normales Spiel.",
  "Sin semilla: partida normal.", "Pas de graine\u00a0: partie normale.",
  "Nessun seme: partita normale.", "Sem semente: jogo normal.",
  "\u041d\u0435\u0442 seed: \u043e\u0431\u044b\u0447\u043d\u0430\u044f \u0438\u0433\u0440\u0430.",
  "\u0644\u0627 \u0628\u0630\u0631\u0629: \u0644\u0639\u0628\u0629 \u0639\u0627\u062f\u064a\u0629.",
  "\u0915\u094b\u0908 \u0938\u0940\u0921 \u0928\u0939\u0940\u0902: \u0938\u093e\u0927\u093e\u0930\u0923 \u0916\u0947\u0932\u0964",
  "Tiada benih: permainan biasa.", "\u65e0\u79cd\u5b50\uff1a\u666e\u901a\u6e38\u620f\u3002"),
 "randomizer_open_page": (
  "Randomizer settings", "Randomizer-Einstellungen", "Ajustes del aleatorio", "R\u00e9glages al\u00e9atoires",
  "Impostazioni casuali", "Defini\u00e7\u00f5es do aleat\u00f3rio",
  "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 \u0440\u0430\u043d\u0434\u043e\u043c\u0430\u0439\u0437\u0435\u0440\u0430",
  "\u0625\u0639\u062f\u0627\u062f\u0627\u062a \u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629",
  "\u0930\u0948\u0902\u0921\u092e\u093e\u0907\u091c\u093c\u0930 \u0938\u0947\u091f\u093f\u0902\u0917",
  "Tetapan pengacak", "\u968f\u673a\u8bbe\u7f6e"),
 "randomizer_chance_short": (
  "%1$d%%", "%1$d%%", "%1$d%%", "%1$d%%", "%1$d%%", "%1$d%%",
  "%1$d%%", "%1$d%%", "%1$d%%", "%1$d%%", "%1$d%%"),
 "randomizer_weapons_short": (
  "Weapons %1$d", "Waffen %1$d", "Armas %1$d", "Armes %1$d", "Armi %1$d", "Armas %1$d",
  "\u041e\u0440\u0443\u0436\u0438\u0435 %1$d", "\u0623\u0633\u0644\u062d\u0629 %1$d",
  "\u0939\u0925\u093f\u092f\u093e\u0930 %1$d", "Senjata %1$d", "\u6b66\u5668 %1$d"),
 "randomizer_minus": (
  "\u2212", "\u2212", "\u2212", "\u2212", "\u2212", "\u2212", "\u2212", "\u2212", "\u2212", "\u2212", "\u2212"),
 "randomizer_plus": (
  "+", "+", "+", "+", "+", "+", "+", "+", "+", "+", "+"),
 "randomizer_start": (  "Start game", "Spiel starten", "Empezar partida", "Lancer le jeu", "Avvia il gioco", "Iniciar jogo",
  "\u041d\u0430\u0447\u0430\u0442\u044c \u0438\u0433\u0440\u0443", "\u0628\u062f\u0621 \u0627\u0644\u0644\u0639\u0628\u0629",
  "\u0916\u0947\u0932 \u0936\u0941\u0930\u0942 \u0915\u0930\u0947\u0902", "Mula permainan", "\u5f00\u59cb\u6e38\u620f"),
 "ingame_seed_help": (  "The randomizers are set on the Randomizer page of the title screen, before the game starts: one seed per run, "
  "and the same seed always gives the same game. This menu shows only which seed you are playing; turn the "
  "randomizers on or off there.",
  "Die Randomizer stellst du auf der Randomizer-Seite des Titelbildschirms ein, bevor das Spiel startet: ein Seed "
  "pro Durchlauf, und derselbe Seed ergibt immer dasselbe Spiel. Dieses Men\u00fc zeigt nur, welchen Seed du "
  "spielst; ein- und ausschalten tust du sie dort.",
  "Los aleatorizadores se configuran en la p\u00e1gina Aleatorio de la pantalla de t\u00edtulo, antes de empezar: "
  "una semilla por partida, y la misma semilla da siempre el mismo juego. Este men\u00fa solo muestra qu\u00e9 "
  "semilla juegas; act\u00edvalos all\u00ed.",
  "Les modes al\u00e9atoires se r\u00e8glent sur la page Al\u00e9atoire de l\u2019\u00e9cran titre, avant de "
  "commencer\u00a0: une graine par partie, et la m\u00eame graine donne toujours le m\u00eame jeu. Ce menu indique "
  "seulement la graine utilis\u00e9e\u00a0; activez-les l\u00e0-bas.",
  "I casualizzatori si impostano nella pagina Casuale della schermata del titolo, prima di iniziare: un seme per "
  "partita, e lo stesso seme d\u00e0 sempre lo stesso gioco. Questo menu mostra solo il seme in uso; attivali l\u00ec.",
  "Os aleatorizadores configuram-se na p\u00e1gina Aleat\u00f3rio do ecr\u00e3 de t\u00edtulo, antes de come\u00e7ar: "
  "uma semente por partida, e a mesma semente d\u00e1 sempre o mesmo jogo. Este menu mostra apenas a semente em uso; "
  "liga-os l\u00e1.",
  "\u0420\u0430\u043d\u0434\u043e\u043c\u0430\u0439\u0437\u0435\u0440\u044b \u043d\u0430\u0441\u0442\u0440\u0430\u0438\u0432\u0430\u044e\u0442\u0441\u044f "
  "\u043d\u0430 \u0441\u0442\u0440\u0430\u043d\u0438\u0446\u0435 \u0420\u0430\u043d\u0434\u043e\u043c\u0430\u0439\u0437\u0435\u0440 "
  "\u044d\u043a\u0440\u0430\u043d\u0430 \u0437\u0430\u0433\u043e\u043b\u043e\u0432\u043a\u0430, \u0434\u043e "
  "\u043d\u0430\u0447\u0430\u043b\u0430 \u0438\u0433\u0440\u044b: \u043e\u0434\u0438\u043d seed \u043d\u0430 "
  "\u043f\u0440\u043e\u0445\u043e\u0436\u0434\u0435\u043d\u0438\u0435, \u0438 \u0442\u043e\u0442 \u0436\u0435 "
  "seed \u0432\u0441\u0435\u0433\u0434\u0430 \u0434\u0430\u0451\u0442 \u0442\u0443 \u0436\u0435 \u0438\u0433\u0440\u0443. "
  "\u0417\u0434\u0435\u0441\u044c \u0432\u0438\u0434\u0435\u043d \u0442\u043e\u043b\u044c\u043a\u043e "
  "\u0442\u0435\u043a\u0443\u0449\u0438\u0439 seed; \u0432\u043a\u043b\u044e\u0447\u0430\u044e\u0442 \u0438\u0445 \u0442\u0430\u043c.",
  "\u062a\u064f\u0636\u0628\u0637 \u0627\u0644\u0639\u0646\u0627\u0635\u0631 \u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629 "
  "\u0641\u064a \u0635\u0641\u062d\u0629 \u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629 "
  "\u0628\u0634\u0627\u0634\u0629 \u0627\u0644\u0639\u0646\u0648\u0627\u0646\u060c \u0642\u0628\u0644 "
  "\u0628\u062f\u0621 \u0627\u0644\u0644\u0639\u0628\u0629: \u0628\u0630\u0631\u0629 \u0648\u0627\u062d\u062f\u0629 "
  "\u0644\u0643\u0644 \u062c\u0648\u0644\u0629\u060c \u0648\u0646\u0641\u0633 \u0627\u0644\u0628\u0630\u0631\u0629 "
  "\u062a\u0639\u0637\u064a \u062f\u0627\u0626\u0645\u0627\u064b \u0646\u0641\u0633 \u0627\u0644\u0644\u0639\u0628\u0629. "
  "\u0647\u0630\u0647 \u0627\u0644\u0642\u0627\u0626\u0645\u0629 \u062a\u0639\u0631\u0636 \u0627\u0644\u0628\u0630\u0631\u0629 "
  "\u0627\u0644\u062d\u0627\u0644\u064a\u0629 \u0641\u0642\u0637\u061b \u0641\u0639\u0651\u0644\u0647\u0627 \u0647\u0646\u0627\u0643.",
  "\u0930\u0948\u0902\u0921\u092e\u093e\u0907\u091c\u093c\u0930 \u091f\u093e\u0907\u091f\u0932 "
  "\u0938\u094d\u0915\u094d\u0930\u0940\u0928 \u0915\u0947 \u0930\u0948\u0902\u0921\u092e\u093e\u0907\u091c\u093c\u0930 "
  "\u092a\u0947\u091c \u092a\u0930 \u0938\u0947\u091f \u0939\u094b\u0924\u0947 \u0939\u0948\u0902, \u0916\u0947\u0932 "
  "\u0936\u0941\u0930\u0942 \u0939\u094b\u0928\u0947 \u0938\u0947 \u092a\u0939\u0932\u0947: \u0939\u0930 \u0916\u0947\u0932 "
  "\u0915\u0947 \u0932\u093f\u090f \u090f\u0915 \u0938\u0940\u0921, \u0914\u0930 \u0935\u0939\u0940 \u0938\u0940\u0921 "
  "\u0939\u092e\u0947\u0936\u093e \u0935\u0939\u0940 \u0916\u0947\u0932 \u0926\u0947\u0924\u093e \u0939\u0948\u0964 "
  "\u092f\u0939 \u092e\u0947\u0928\u0942 \u0915\u0947\u0935\u0932 \u0926\u093f\u0916\u093e\u0924\u093e \u0939\u0948 "
  "\u0915\u093f \u0915\u094c\u0928 \u0938\u093e \u0938\u0940\u0921 \u0916\u0947\u0932 \u0930\u0939\u0947 \u0939\u0948\u0902; "
  "\u091a\u093e\u0932\u0942 \u0935\u0939\u093e\u0902 \u0915\u0930\u0947\u0902\u0964",
  "Pengacak ditetapkan pada halaman Pengacak di skrin tajuk, sebelum permainan bermula: satu benih setiap "
  "permainan, dan benih yang sama selalu memberi permainan yang sama. Menu ini hanya menunjukkan benih yang sedang "
  "dimainkan; hidupkan di sana.",
  "\u968f\u673a\u9879\u5728\u6807\u9898\u5c4f\u5e55\u7684\u968f\u673a\u9875\u9762\u4e0a\u8bbe\u7f6e\uff0c"
  "\u5728\u6e38\u620f\u5f00\u59cb\u4e4b\u524d\uff1a\u6bcf\u4e00\u8f6e\u4e00\u4e2a\u79cd\u5b50\uff0c\u76f8"
  "\u540c\u7684\u79cd\u5b50\u603b\u4f1a\u5f97\u5230\u76f8\u540c\u7684\u6e38\u620f\u3002\u672c\u83dc\u5355"
  "\u53ea\u663e\u793a\u5f53\u524d\u4f7f\u7528\u7684\u79cd\u5b50\uff1b\u8bf7\u5728\u90a3\u91cc\u5f00\u5173\u3002"),
 "randomizer_enemy_options_short": (
  "Enemies %1$d", "Gegner %1$d", "Enemigos %1$d", "Ennemis %1$d", "Nemici %1$d", "Inimigos %1$d",
  "\u0412\u0440\u0430\u0433\u0438 %1$d", "\u0627\u0644\u0623\u0639\u062f\u0627\u0621 %1$d",
  "\u0926\u0941\u0936\u094d\u092e\u0928 %1$d", "Musuh %1$d", "\u657c\u4eba %1$d"),
 "randomizer_enemy_options_title": (
  "Enemy settings", "Gegner-Einstellungen", "Ajustes de enemigos", "R\u00e9glages des ennemis",
  "Impostazioni nemici", "Ajustes de inimigos", "\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438 "
  "\u0432\u0440\u0430\u0433\u043e\u0432", "\u0625\u0639\u062f\u0627\u062f\u0627\u062a \u0627\u0644\u0623\u0639\u062f\u0627\u0621",
  "\u0926\u0941\u0936\u094d\u092e\u0928 \u0938\u0947\u091f\u093f\u0902\u0917", "Pengaturan musuh",
  "\u657c\u4eba\u8bbe\u7f6e"),
 "randomizer_enemy_pool_title": (
  "Which enemy types may appear", "Welche Gegnerarten d\u00fcrfen erscheinen",
  "Qu\u00e9 tipos de enemigos pueden aparecer", "Quels types d'ennemis peuvent appara\u00eetre",
  "Quali tipi di nemici possono apparire", "Quais tipos de inimigos podem aparecer",
  "\u041a\u0430\u043a\u0438\u0435 \u0442\u0438\u043f\u044b \u0432\u0440\u0430\u0433\u043e\u0432 "
  "\u043c\u043e\u0433\u0443\u0442 \u043f\u043e\u044f\u0432\u043b\u044f\u0442\u044c\u0441\u044f",
  "\u0623\u064a \u0623\u0646\u0648\u0627\u0639 \u0627\u0644\u0623\u0639\u062f\u0627\u0621 \u064a\u0645\u0643\u0646 "
  "\u0623\u0646 \u062a\u0638\u0647\u0631",
  "\u0915\u094c\u0928 \u0938\u0947 \u0926\u0941\u0936\u094d\u092e\u0928 \u092a\u094d\u0930\u0915\u093e\u0930 "
  "\u0926\u093f\u0916 \u0938\u0915\u0924\u0947 \u0939\u0948\u0902", "Jenis musuh yang boleh muncul",
  "\u54ea\u4e9b\u657c\u4eba\u53ef\u4ee5\u51fa\u73b0"),
 "randomizer_enemy_mix_title": (
  "How often the rest roll a mix", "Wie oft die \u00fcbrigen eine Mischung w\u00fcrfeln",
  "Con qu\u00e9 frecuencia saca una mezcla el resto",
  "\u00c0 quelle fr\u00e9quence le reste tire une mixture",
  "Con quale frequenza il resto tira una miscela",
  "Com que frequ\u00eancia o restante tira uma mistura",
  "\u041a\u0430\u043a \u0447\u0430\u0441\u0442\u043e \u043e\u0441\u0442\u0430\u043b\u044c\u043d\u044b\u0435 "
  "\u0431\u0440\u043e\u0441\u0430\u044e\u0442 \u0441\u043c\u0435\u0441\u044c",
  "\u0643\u0645 \u0645\u0631\u0629 \u064a\u0633\u062d\u0628 \u0627\u0644\u0628\u0627\u0642\u064a "
  "\u062e\u0644\u0637\u064b\u0627",
  "\u092c\u093e\u0915\u093c\u0940 \u0915\u093f\u0924\u0928\u0940 \u092c\u093e\u0930 \u092e\u093f\u0936\u094d\u0930\u0923 "
  "\u0932\u0949\u0917 \u0915\u0930\u0924\u0947 \u0939\u0948\u0902", "Betapa kerap yang lain mengambil campuran",
  "\u5176\u4f59\u657c\u4eba\u51fa\u73b0\u7279\u6b8a\u5c5e\u6027\u7684\u9891\u7387"),
 "randomizer_enemy_variety": (
  "\u00b7 Max. enemy types per room", "\u00b7 H\u00f6chstens Gegnerarten je Raum",
  "\u00b7 M\u00e1x. tipos de enemigos por habitaci\u00f3n",
  "\u00b7 Max. types d'ennemis par pi\u00e8ce", "\u00b7 Max. tipi di nemici per stanza",
  "\u00b7 M\u00e1x. tipos de inimigos por sala",
  "\u00b7 \u041c\u0430\u043a\u0441. \u0442\u0438\u043f\u044b \u0432\u0440\u0430\u0433\u043e\u0432 \u0432 "
  "\u043a\u043e\u043c\u043d\u0430\u0442\u0435",
  "\u00b7 \u062d\u062f \u0623\u0646\u0648\u0627\u0639 \u0627\u0644\u0623\u0639\u062f\u0627\u0621 \u0641\u064a "
  "\u0627\u0644\u063a\u0631\u0641\u0629",
  "\u00b7 \u092a\u094d\u0930\u0924\u093f \u0915\u092e\u0930\u0947 \u0926\u0941\u0936\u094d\u092e\u0928 \u092a\u094d"
  "\u0930\u0915\u093e\u0930",
  "\u00b7 Maks. jenis musuh per bilik", "\u00b7 \u6bcf\u4e2a\u623f\u95f4\u7684\u6700\u5927\u657c\u4eba\u79cd\u7c7b"),
 "randomizer_one_in": (
  "1 in %1$d", "1 von %1$d", "1 de %1$d", "1 sur %1$d", "1 su %1$d", "1 em %1$d", "1 \u0438\u0437 %1$d",
  "1 \u0645\u0646 %1$d", "1 \u092e\u0647\u0902 %1$d", "1 daripada %1$d", "1 \u5728 %1$d"),
 "randomizer_enemy_unsafe": (
  "Unsafe mode: also randomize the cautious rooms", "Unsicherer Modus: auch die vorsichtigen R\u00e4ume",
  "Modo inseguro: tambi\u00e9n las habitaciones cautelosas",
  "Mode non s\u00fbr : aussi les pi\u00e8ces prudentes", "Modalit\u00e0 non sicura: anche le stanze caute",
  "Modo inseguro: tamb\u00e9m as salas cautelosas",
  "\u041d\u0435\u0431\u0435\u0437\u043e\u043f\u0430\u0441\u043d\u044b\u0439 \u0440\u0435\u0436\u0438\u043c: \u0438 \u043e\u0441\u0442\u043e\u0440\u043e\u0436\u043d\u044b\u0435 \u043a\u043e\u043c\u043d\u0430\u0442\u044b",
  "\u0648\u0636\u0639 \u063a\u064a\u0631 \u0622\u0645\u0646: \u0639\u0634\u0648\u0627\u0626\u064a\u0629 \u0627\u0644\u063a\u0631\u0641 \u0627\u0644\u062d\u0630\u0631\u0629 \u0623\u064a\u0636\u064b\u0627",
  "\u0905\u0938\u0941\u0930\u0915\u094d\u0937\u093f\u0924 \u092e\u094b\u0921: \u0938\u093e\u0935\u0927\u093e\u0928 \u0935\u093e\u0932\u0947 \u0915\u092e\u0930\u0947 \u092d\u0940",
  "Modus tidak selamat: juga bilik berjaga-jaga",
  "\u4e0d\u5b89\u5168\u06a21\u5f0f\u6216\u968f\u673a\u5316\u8c28\u614e\u7684\u623f\u95f4"),
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
