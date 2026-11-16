import re

base = "port/android-decomp/app/src/main/res"
wave_words = {
    "values": " and waves",
    "values-de": " und Wellen",
    "values-es": " y las oleadas",
    "values-fr": " et les vagues",
    "values-hi": " और लहरें",
    "values-in": " dan gelombang",
    "values-it": " e ondate",
    "values-pt": " e ondas",
    "values-ru": " и волны",
    "values-zh": "和敌人波次",
    "values-ar": " والموجات",
}
for f, w in wave_words.items():
    p = f"{base}/{f}/strings_ingame.xml"
    s = open(p, encoding="utf-8").read()
    m = re.search(r'(<string name="ingame_cheat_random_enemies_help">)(.*?)(</string>)', s, re.S)
    if not m:
        print(f, "NOT FOUND")
        continue
    body = m.group(2)
    if w in body:
        body = body.replace(w, "", 1)
        open(p, "w", encoding="utf-8").write(s[: m.start(2)] + body + s[m.end(2):])
        print(f, "wave word removed")
    else:
        print(f, "wave word NOT FOUND, left as is")
