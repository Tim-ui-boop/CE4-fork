#!/usr/bin/env python3
"""Adds the "Random merchant stock" strings to every language of the in-game menu
(port/android-decomp/app/src/main/res/values*/strings_ingame.xml), after the "Random enemies" pair.

The switch appears on the page "Cheats & Mods" next to "Random enemies"; its label is short (it sits in a row of two
cells) and its help text names what the merchant then sells and what stays as it is.

    port/android-decomp/tools/add_merchant_strings.py [--check]
"""

import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ANCHOR = 'name="ingame_cheat_random_enemies_help"'

# label, help (the help names what is sold and what is not; the same limits as the other randomizers)
TEXT = {
    "values": (
        "Random merchant stock",
        "Random merchant stock: the merchant sells items drawn from one pool covering the whole game, so a weapon "
        "that otherwise comes late (or only in a cleared game, like the Handcannon or the Chicago Typewriter) can "
        "already stand at the first merchant. Weapons, ammunition, grenades, recovery items, pesetas, treasures and "
        "weapon parts are drawn; keys, files, maps, case upgrades and other story items never are, so the game can "
        "always be finished. Prices and tune-ups stay the game's own. A merchant looks the same each time until you "
        "switch this off and on again; saves stay the game's own. Not in Assignment Ada and The Mercenaries.",
    ),
    "values-de": (
        "Zuf\u00e4lliger H\u00e4ndler-Bestand",
        "Zuf\u00e4lliger H\u00e4ndler-Bestand: Der H\u00e4ndler verkauft Waren aus einem gemeinsamen Vorrat des ganzen "
        "Spiels, daher kann eine Waffe, die sonst erst sp\u00e4t kommt (oder nur im zweiten Durchgang, wie die "
        "Handkanone oder die Chicago Typewriter), schon beim ersten H\u00e4ndler stehen. Gezogen werden Waffen, "
        "Munition, Granaten, Heilmittel, Peseten, Sch\u00e4tze und Waffenteile; Schl\u00fcssel, Akten, Karten, "
        "Koffer-Upgrades und andere Gegenst\u00e4nde der Geschichte nie, damit das Spiel immer zu schaffen ist. Preise "
        "und Verbesserungen bleiben die des Spiels. Ein H\u00e4ndler sieht jedes Mal gleich aus, bis du das aus- und "
        "wieder einschaltest; Spielst\u00e4nde bleiben die des Spiels. Nicht in Assignment Ada und The Mercenaries.",
    ),
    "values-es": (
        "Stock aleatorio del mercader",
        "Stock aleatorio del mercader: el mercader vende art\u00edculos de un fondo com\u00fan de todo el juego, as\u00ed "
        "que un arma que normalmente llega tarde (o solo en una partida completada, como el Handcannon o la Chicago "
        "Typewriter) puede estar ya en el primer mercader. Se sortean armas, munici\u00f3n, granadas, objetos "
        "curativos, pesetas, tesoros y piezas de armas; nunca llaves, archivos, mapas, mejoras del malet\u00edn ni "
        "otros objetos de la historia, para que el juego siempre se pueda terminar. Los precios y las mejoras siguen "
        "siendo los del juego. Un mercader se ve igual cada vez hasta que lo apagues y lo vuelvas a encender; las "
        "partidas guardadas siguen siendo las del juego. No en Assignment Ada ni en The Mercenaries.",
    ),
    "values-fr": (
        "Stock al\u00e9atoire du marchand",
        "Stock al\u00e9atoire du marchand\u00a0: le marchand vend des objets tir\u00e9s d\u2019une r\u00e9serve "
        "commune \u00e0 tout le jeu, si bien qu\u2019une arme qui arrive normalement tard (ou seulement en partie "
        "termin\u00e9e, comme le Handcannon ou la Chicago Typewriter) peut d\u00e9j\u00e0 se trouver chez le premier "
        "marchand. Sont tir\u00e9s les armes, munitions, grenades, objets de soin, pesetas, tr\u00e9sors et pi\u00e8ces "
        "d\u2019arme\u00a0; jamais les cl\u00e9s, dossiers, cartes, am\u00e9liorations de mallette ni autres objets "
        "sc\u00e9naris\u00e9s, afin que le jeu puisse toujours \u00eatre termin\u00e9. Les prix et les am\u00e9liorations "
        "restent ceux du jeu. Un marchand est identique \u00e0 chaque fois jusqu\u2019\u00e0 ce que vous l\u2019\u00e9teigniez "
        "et le rallumiez\u00a0; les sauvegardes restent celles du jeu. Pas dans Assignment Ada ni The Mercenaries.",
    ),
    "values-it": (
        "Scorte casuali del mercante",
        "Scorte casuali del mercante: il mercante vende oggetti presi da una riserva comune di tutto il gioco, quindi "
        "un\u2019arma che di solito arriva tardi (o solo a partita conclusa, come lo Handcannon o la Chicago Typewriter) "
        "pu\u00f2 gi\u00e0 trovarsi dal primo mercante. Vengono estratti armi, munizioni, granate, oggetti curativi, "
        "pesetas, tesori e pezzi d\u2019arma; mai chiavi, fascicoli, mappe, potenziamenti della valigetta o altri "
        "oggetti della storia, cos\u00ec il gioco si pu\u00f2 sempre finire. I prezzi e i potenziamenti restano quelli "
        "del gioco. Un mercante \u00e8 identico ogni volta finch\u00e9 non lo spegni e lo riaccendi; i salvataggi "
        "restano quelli del gioco. Non in Assignment Ada e The Mercenaries.",
    ),
    "values-pt": (
        "Stock aleat\u00f3rio do mercador",
        "Stock aleat\u00f3rio do mercador: o mercador vende itens tirados de uma reserva comum de todo o jogo, por isso "
        "uma arma que normalmente chega tarde (ou s\u00f3 numa partida conclu\u00edda, como a Handcannon ou a Chicago "
        "Typewriter) pode j\u00e1 estar no primeiro mercador. S\u00e3o tiradas armas, muni\u00e7\u00f5es, granadas, "
        "itens de cura, pesetas, tesouros e pe\u00e7as de armas; nunca chaves, ficheiros, mapas, melhorias da mala ou "
        "outros itens da hist\u00f3ria, para que o jogo se possa sempre terminar. Os pre\u00e7os e as melhorias "
        "continuam a ser os do jogo. Um mercador fica igual de cada vez at\u00e9 desligares e voltares a ligar; os "
        "jogos guardados continuam a ser os do jogo. N\u00e3o em Assignment Ada nem em The Mercenaries.",
    ),
    "values-ru": (
        "\u0421\u043b\u0443\u0447\u0430\u0439\u043d\u044b\u0439 \u0442\u043e\u0432\u0430\u0440 \u0442\u043e\u0440\u0433\u043e\u0432\u0446\u0430",
        "\u0421\u043b\u0443\u0447\u0430\u0439\u043d\u044b\u0439 \u0442\u043e\u0432\u0430\u0440 \u0442\u043e\u0440\u0433\u043e\u0432\u0446\u0430: "
        "\u0442\u043e\u0440\u0433\u043e\u0432\u0435\u0446 \u043f\u0440\u043e\u0434\u0430\u0451\u0442 \u043f\u0440\u0435\u0434\u043c\u0435\u0442\u044b "
        "\u0438\u0437 \u043e\u0431\u0449\u0435\u0433\u043e \u0437\u0430\u043f\u0430\u0441\u0430 \u0432\u0441\u0435\u0439 "
        "\u0438\u0433\u0440\u044b, \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u043e\u0440\u0443\u0436\u0438\u0435, "
        "\u043a\u043e\u0442\u043e\u0440\u043e\u0435 \u043e\u0431\u044b\u0447\u043d\u043e \u043f\u043e\u044f\u0432\u043b\u044f\u0435\u0442\u0441\u044f "
        "\u043f\u043e\u0437\u0434\u043d\u043e (\u0438\u043b\u0438 \u0442\u043e\u043b\u044c\u043a\u043e \u0432 "
        "\u043f\u043e\u0432\u0442\u043e\u0440\u043d\u043e\u043c \u043f\u0440\u043e\u0445\u043e\u0436\u0434\u0435\u043d\u0438\u0438, "
        "\u043a\u0430\u043a Handcannon \u0438\u043b\u0438 Chicago Typewriter), \u043c\u043e\u0436\u0435\u0442 "
        "\u0441\u0442\u043e\u044f\u0442\u044c \u0443\u0436\u0435 \u0443 \u043f\u0435\u0440\u0432\u043e\u0433\u043e "
        "\u0442\u043e\u0440\u0433\u043e\u0432\u0446\u0430. \u0412\u044b\u043f\u0430\u0434\u0430\u044e\u0442 "
        "\u043e\u0440\u0443\u0436\u0438\u0435, \u043f\u0430\u0442\u0440\u043e\u043d\u044b, \u0433\u0440\u0430\u043d\u0430\u0442\u044b, "
        "\u0441\u0440\u0435\u0434\u0441\u0442\u0432\u0430 \u043b\u0435\u0447\u0435\u043d\u0438\u044f, \u043f\u0435\u0441\u0435\u0442\u044b, "
        "\u0441\u043e\u043a\u0440\u043e\u0432\u0438\u0449\u0430 \u0438 \u0447\u0430\u0441\u0442\u0438 \u043e\u0440\u0443\u0436\u0438\u044f; "
        "\u043d\u0438\u043a\u043e\u0433\u0434\u0430 \u043a\u043b\u044e\u0447\u0438, \u0434\u043e\u043a\u0443\u043c\u0435\u043d\u0442\u044b, "
        "\u043a\u0430\u0440\u0442\u044b, \u0443\u043b\u0443\u0447\u0448\u0435\u043d\u0438\u044f \u0447\u0435\u043c\u043e\u0434\u0430\u043d\u0430 "
        "\u0438 \u043f\u0440\u043e\u0447\u0438\u0435 \u0441\u044e\u0436\u0435\u0442\u043d\u044b\u0435 "
        "\u043f\u0440\u0435\u0434\u043c\u0435\u0442\u044b, \u0447\u0442\u043e\u0431\u044b \u0438\u0433\u0440\u0443 "
        "\u043c\u043e\u0436\u043d\u043e \u0431\u044b\u043b\u043e \u0432\u0441\u0435\u0433\u0434\u0430 "
        "\u043f\u0440\u043e\u0439\u0442\u0438. \u0426\u0435\u043d\u044b \u0438 \u0443\u043b\u0443\u0447\u0448\u0435\u043d\u0438\u044f "
        "\u043e\u0441\u0442\u0430\u044e\u0442\u0441\u044f \u0438\u0433\u0440\u043e\u0432\u044b\u043c\u0438. "
        "\u0422\u043e\u0440\u0433\u043e\u0432\u0435\u0446 \u0432\u044b\u0433\u043b\u044f\u0434\u0438\u0442 "
        "\u043e\u0434\u0438\u043d\u0430\u043a\u043e\u0432\u043e \u043a\u0430\u0436\u0434\u044b\u0439 \u0440\u0430\u0437, "
        "\u043f\u043e\u043a\u0430 \u0432\u044b \u043d\u0435 \u0432\u044b\u043a\u043b\u044e\u0447\u0438\u0442\u0435 "
        "\u0438 \u043d\u0435 \u0432\u043a\u043b\u044e\u0447\u0438\u0442\u0435 \u0441\u043d\u043e\u0432\u0430; "
        "\u0441\u043e\u0445\u0440\u0430\u043d\u0435\u043d\u0438\u044f \u043e\u0441\u0442\u0430\u044e\u0442\u0441\u044f "
        "\u0438\u0433\u0440\u043e\u0432\u044b\u043c\u0438. \u041d\u0435 \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 "
        "\u0432 Assignment Ada \u0438 The Mercenaries.",
    ),
    "values-ar": (
        "\u0628\u0636\u0627\u0639\u0629 \u0627\u0644\u062a\u0627\u062c\u0631 \u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629",
        "\u0628\u0636\u0627\u0639\u0629 \u0627\u0644\u062a\u0627\u062c\u0631 \u0627\u0644\u0639\u0634\u0648\u0627\u0626\u064a\u0629: "
        "\u064a\u0628\u064a\u0639 \u0627\u0644\u062a\u0627\u062c\u0631 \u0639\u0646\u0627\u0635\u0631 \u0645\u0646 "
        "\u0645\u062e\u0632\u0648\u0646 \u0645\u0634\u062a\u0631\u0643 \u0644\u0644\u062c\u0645\u064a\u0639 \u0637\u0648\u0627\u0644 "
        "\u0627\u0644\u0644\u0639\u0628\u0629\u060c \u0644\u0630\u0627 \u0642\u062f \u064a\u0643\u0648\u0646 "
        "\u0633\u0644\u0627\u062d \u064a\u0623\u062a\u064a \u0639\u0627\u062f\u0629\u064b \u0645\u062a\u0623\u062e\u0631\u0627\u064b "
        "(\u0623\u0648 \u0641\u064a \u0627\u0644\u062c\u0648\u0644\u0629 \u0627\u0644\u062b\u0627\u0646\u064a\u0629 "
        "\u0641\u0642\u0637\u060c \u0645\u062b\u0644 Handcannon \u0623\u0648 Chicago Typewriter) \u0645\u0648\u062c\u0648\u062f\u0627\u064b "
        "\u0644\u062f\u0649 \u0623\u0648\u0644 \u062a\u0627\u062c\u0631. \u062a\u064f\u0633\u062d\u0628 "
        "\u0627\u0644\u0623\u0633\u0644\u062d\u0629 \u0648\u0627\u0644\u0630\u062e\u064a\u0631\u0629 "
        "\u0648\u0627\u0644\u0642\u0646\u0627\u0628\u0644 \u0648\u0623\u062f\u0648\u0627\u062a "
        "\u0627\u0644\u0639\u0644\u0627\u062c \u0648\u0627\u0644\u0628\u064a\u0632\u064a\u062a\u0627\u0633 "
        "\u0648\u0627\u0644\u0643\u0646\u0648\u0632 \u0648\u0642\u0637\u0639 \u0627\u0644\u0623\u0633\u0644\u062d\u0629\u061b "
        "\u0648\u0644\u0627 \u062a\u064f\u0633\u062d\u0628 \u0623\u0628\u062f\u0627\u064b "
        "\u0627\u0644\u0645\u0641\u0627\u062a\u064a\u062d \u0648\u0627\u0644\u0645\u0644\u0641\u0627\u062a "
        "\u0648\u0627\u0644\u062e\u0631\u0627\u0626\u0637 \u0648\u062a\u0631\u0642\u064a\u0627\u062a "
        "\u0627\u0644\u062d\u0642\u064a\u0628\u0629 \u0648\u063a\u064a\u0631\u0647\u0627 \u0645\u0646 "
        "\u0639\u0646\u0627\u0635\u0631 \u0627\u0644\u0642\u0635\u0629\u060c \u0644\u0643\u064a "
        "\u064a\u0645\u0643\u0646 \u0625\u0646\u0647\u0627\u0621 \u0627\u0644\u0644\u0639\u0628\u0629 "
        "\u062f\u0627\u0626\u0645\u0627\u064b. \u062a\u0628\u0642\u0649 \u0627\u0644\u0623\u0633\u0639\u0627\u0631 "
        "\u0648\u0627\u0644\u062a\u0631\u0642\u064a\u0627\u062a \u0643\u0645\u0627 \u0641\u064a "
        "\u0627\u0644\u0644\u0639\u0628\u0629. \u064a\u0628\u062f\u0648 \u0627\u0644\u062a\u0627\u062c\u0631 "
        "\u0645\u062a\u0634\u0627\u0628\u0647\u0627\u064b \u0641\u064a \u0643\u0644 \u0645\u0631\u0629 "
        "\u062d\u062a\u0649 \u062a\u064f\u0637\u0641\u0626\u0647 \u0648\u062a\u064f\u0634\u063a\u0651\u0644\u0647 "
        "\u0645\u062c\u062f\u062f\u0627\u064b\u061b \u0648\u062a\u0628\u0642\u0649 "
        "\u0627\u0644\u062d\u0641\u0638\u0627\u062a \u062d\u0641\u0638\u0627\u062a \u0627\u0644\u0644\u0639\u0628\u0629. "
        "\u0644\u0627 \u064a\u0639\u0645\u0644 \u0641\u064a Assignment Ada \u0648 The Mercenaries.",
    ),
    "values-hi": (
        "\u092f\u093e\u0926\u0943\u091a\u094d\u091b\u093f\u0915 \u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940 \u092e\u093e\u0932",
        "\u092f\u093e\u0926\u0943\u091a\u094d\u091b\u093f\u0915 \u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940 "
        "\u092e\u093e\u0932: \u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940 \u092a\u0942\u0930\u0947 "
        "\u0916\u0947\u0932 \u0915\u0947 \u090f\u0915 \u0938\u093e\u091d\u093e \u092d\u0902\u0921\u093e\u0930 "
        "\u0938\u0947 \u0938\u093e\u092e\u093e\u0928 \u092c\u0947\u091a\u0924\u093e \u0939\u0948, "
        "\u0907\u0938\u0932\u093f\u090f \u091c\u094b \u0939\u0925\u093f\u092f\u093e\u0930 \u0906\u092e "
        "\u0924\u094c\u0930 \u092a\u0930 \u092c\u093e\u0926 \u092e\u0947\u0902 \u0906\u0924\u093e \u0939\u0948 "
        "(\u092f\u093e \u0915\u0947\u0935\u0932 \u0926\u0942\u0938\u0930\u0940 \u092c\u093e\u0930 \u0916\u0947\u0932\u0928\u0947 "
        "\u092a\u0930, \u091c\u0948\u0938\u0947 Handcannon \u092f\u093e Chicago Typewriter), \u0935\u0939 "
        "\u092a\u0939\u0932\u0947 \u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940 \u092a\u0930 \u0939\u0940 "
        "\u0939\u094b \u0938\u0915\u0924\u093e \u0939\u0948\u0964 \u0939\u0925\u093f\u092f\u093e\u0930, "
        "\u0917\u094b\u0932\u0940-\u092c\u093e\u0930\u0942\u0926, \u0917\u094d\u0930\u0947\u0928\u0947\u0921, "
        "\u0907\u0932\u093e\u091c \u0915\u0940 \u0935\u0938\u094d\u0924\u0941\u090f\u0902, \u092a\u0947\u0938\u0947\u0924\u093e\u0938, "
        "\u0916\u091c\u093e\u0928\u0947 \u0914\u0930 \u0939\u0925\u093f\u092f\u093e\u0930 \u0915\u0947 "
        "\u092a\u0941\u0930\u091c\u0947 \u091a\u0941\u0928\u0947 \u091c\u093e\u0924\u0947 \u0939\u0948\u0902; "
        "\u091a\u093e\u092c\u0940, \u092b\u093c\u093e\u0907\u0932\u0947\u0902, \u0928\u0915\u094d\u0936\u0947, "
        "\u092c\u0948\u0917 \u0915\u0947 \u0909\u0928\u094d\u0928\u092f\u0928 \u0914\u0930 \u0905\u0928\u094d\u092f "
        "\u0915\u0939\u093e\u0928\u0940 \u0915\u0940 \u0935\u0938\u094d\u0924\u0941\u090f\u0902 \u0915\u092d\u0940 "
        "\u0928\u0939\u0940\u0902, \u0924\u093e\u0915\u093f \u0916\u0947\u0932 \u0939\u092e\u0947\u0936\u093e "
        "\u092a\u0942\u0930\u093e \u0939\u094b \u0938\u0915\u0947\u0964 \u0915\u0940\u092e\u0924\u0947\u0902 "
        "\u0914\u0930 \u0909\u0928\u094d\u0928\u092f\u0928 \u0916\u0947\u0932 \u0915\u0947 \u0939\u0940 "
        "\u0930\u0939\u0924\u0947 \u0939\u0948\u0902\u0964 \u0935\u094d\u092f\u093e\u092a\u093e\u0930\u0940 "
        "\u0939\u0930 \u092c\u093e\u0930 \u0935\u0948\u0938\u093e \u0939\u0940 \u0926\u093f\u0916\u0924\u093e "
        "\u0939\u0948 \u091c\u092c \u0924\u0915 \u0906\u092a \u0907\u0938\u0947 \u092c\u0902\u0926 \u0915\u0930 "
        "\u092b\u093f\u0930 \u0928\u0939\u0940\u0902 \u091a\u093e\u0932\u0942 \u0915\u0930\u0924\u0947; "
        "\u0938\u0947\u0935 \u0916\u0947\u0932 \u0915\u0947 \u0939\u0940 \u0930\u0939\u0924\u0947 \u0939\u0948\u0902\u0964 "
        "Assignment Ada \u0914\u0930 The Mercenaries \u092e\u0947\u0902 \u0928\u0939\u0940\u0902\u0964",
    ),
    "values-in": (
        "Stok pedagang acak",
        "Stok pedagang acak: pedagang menjual barang dari satu kumpulan seluruh permainan, jadi senjata yang biasanya "
        "datang terlambat (atau hanya di permainan yang sudah tamat, seperti Handcannon atau Chicago Typewriter) bisa "
        "sudah ada di pedagang pertama. Yang diambil adalah senjata, amunisi, granat, barang penyembuh, peseta, harta "
        "karun dan suku cadang senjata; kunci, berkas, peta, peningkatan tas dan barang cerita lainnya tidak pernah, "
        "supaya permainan selalu bisa diselesaikan. Harga dan peningkatan tetap milik permainan. Seorang pedagang "
        "terlihat sama setiap kali sampai kamu mematikannya lalu menyalakannya lagi; simpanan tetap milik permainan. "
        "Tidak di Assignment Ada dan The Mercenaries.",
    ),
    "values-zh": (
        "\u968f\u673a\u5546\u4eba\u8d27\u7269",
        "\u968f\u673a\u5546\u4eba\u8d27\u7269\uff1a\u5546\u4eba\u4ece\u6574\u4e2a\u6e38\u620f\u5171\u7528\u7684\u4e00"
        "\u4e2a\u6c60\u5b50\u91cc\u8fdb\u8d27\uff0c\u6240\u4ee5\u901a\u5e38\u5f88\u665a\u624d\u51fa\u73b0\u7684"
        "\u6b66\u5668\uff08\u6216\u53ea\u5728\u901a\u5173\u540e\u51fa\u73b0\u7684\uff0c\u5982 Handcannon \u6216 "
        "Chicago Typewriter\uff09\u53ef\u80fd\u5728\u7b2c\u4e00\u4e2a\u5546\u4eba\u5904\u5c31\u6709\u3002\u62bd"
        "\u53d6\u7684\u662f\u6b66\u5668\u3001\u5b50\u5f39\u3001\u624b\u96f7\u3001\u6cbb\u7597\u54c1\u3001"
        "\u6bd4\u585e\u5854\u3001\u5b9d\u7269\u548c\u6b66\u5668\u90e8\u4ef6\uff1b\u94a5\u5319\u3001\u6863\u6848"
        "\u3001\u5730\u56fe\u3001\u624b\u63d0\u7bb1\u5347\u7ea7\u548c\u5176\u4ed6\u5267\u60c5\u7269\u54c1"
        "\u6c38\u8fdc\u4e0d\u4f1a\uff0c\u56e0\u6b64\u6e38\u620f\u603b\u80fd\u901a\u5173\u3002\u4ef7\u683c"
        "\u548c\u5f3a\u5316\u4ecd\u4e3a\u6e38\u620f\u672c\u6765\u7684\u3002\u5728\u4f60\u5173\u6389\u518d"
        "\u6253\u5f00\u4e4b\u524d\uff0c\u5546\u4eba\u6bcf\u6b21\u770b\u8d77\u6765\u90fd\u4e00\u6837\uff1b"
        "\u5b58\u6863\u4ecd\u4e3a\u6e38\u620f\u672c\u6765\u7684\u3002\u4e0d\u9002\u7528\u4e8e Assignment Ada "
        "\u548c The Mercenaries\u3002",
    ),
}


def escape(text):
    # the checker (check_translations.py) wants apostrophes escaped, and a non-breaking space as an escape
    text = text.replace("\u00a0", "\\u00A0")
    text = text.replace("'", "\\'")
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    check = "--check" in sys.argv
    missing, written = [], 0
    for lang, (label, help_text) in sorted(TEXT.items()):
        path = os.path.join(RES, lang, "strings_ingame.xml")
        if not os.path.exists(path):
            missing.append(lang)
            continue
        text = open(path, encoding="utf-8").read()
        indent = "    "
        label_xml = '%s<string name="ingame_cheat_random_merchant">%s</string>' % (indent, escape(label))
        help_xml = '%s<string name="ingame_cheat_random_merchant_help">%s</string>' % (indent, escape(help_text))
        if 'name="ingame_cheat_random_merchant"' in text:
            # replace both entries in place (the file is re-read, so a fixed text lands on a second run too)
            out = []
            for line in text.split("\n"):
                if 'name="ingame_cheat_random_merchant_help"' in line:
                    out.append(help_xml)
                elif 'name="ingame_cheat_random_merchant"' in line:
                    out.append(label_xml)
                else:
                    out.append(line)
            if not check:
                open(path, "w", encoding="utf-8").write("\n".join(out))
            written += 1
            print("%s: %s" % (lang, "would replace" if check else "replaced"))
            continue
        lines = text.split("\n")
        out = []
        done = False
        for line in lines:
            out.append(line)
            if not done and ANCHOR in line:
                indent = line[:len(line) - len(line.lstrip())]
                out.append('%s<string name="ingame_cheat_random_merchant">%s</string>' % (indent, escape(label)))
                out.append('%s<string name="ingame_cheat_random_merchant_help">%s</string>' % (indent, escape(help_text)))
                done = True
        if not done:
            print("%s: no anchor" % lang)
            continue
        if not check:
            open(path, "w", encoding="utf-8").write("\n".join(out))
        written += 1
        print("%s: %s" % (lang, "would add" if check else "added"))
    if missing:
        print("no file: %s" % ", ".join(missing))
    print("%d language(s) %s" % (written, "to update" if check else "updated"))


if __name__ == "__main__":
    main()
