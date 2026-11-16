import re

base = "port/android-decomp/app/src/main/res"
# one sentence per locale, inserted before the "a room looks the same each time" sentence
sentences = {
    "values": (" Enemies a script brings in later (reinforcements, sieges) can take other enemy types too; with Unsafe mode on, so can the village raid\'s summoned villagers (as Chainsaw Men) and the besiegers of the cabin.", "A room looks the same"),
    "values-de": (" Gegner, die ein Skript später nachbringt (Wellen, Belagerungen), können ebenfalls andere Gegnertypen annehmen; mit Unsicherem Modus auch die herbeigerufenen Dorfbewohner des Dorfüberfalls (als Kettensägenmänner) und die Belagerer der Hütte.", "Ein Raum sieht"),
    "values-es": (" Los enemigos que un guion trae más tarde (oleadas, asedios) también pueden tomar otros tipos; con el Modo inseguro, también los aldeanos convocados del asalto a la aldea (como hombre del sierra) y los asediadores de la cabaña.", "Una sala se ve"),
    "values-fr": (" Les ennemis qu\'un script amène plus tard (vagues, sièges) peuvent aussi prendre d\'autres types ; en Mode non sûr, les villageois invoqués du raid sur le village (en hommes tronçonneuse) et les assiégeants de la cabane aussi.", "Une salle reste"),
    "values-hi": (" जिन दुश्मनों को स्क्रिप्ट बाद में लाती है (लहरें, घेराबंदी) वे भी दूसरे दुश्मन प्रकार बन सकते हैं; असुरक्षित मोड पर, गाँव पर छापे के बुलाए गए ग्रामीण (चेनसॉ मानवों के रूप में) और केबिन के घेरने वाले भी।", "जब तक आप"),
    "values-in": (" Musuh yang dibawa skrip belakangan (gelombang, pengepungan) juga bisa menjadi jenis lain; dengan Modus tidak selamat, begitu juga penduduk desa yang dipanggil dalam serangan desa (sebagai Chainsaw Man) dan pengepung kabin.", "Ruangan terlihat"),
    "values-it": (" I nemici che uno script porta più tardi (ondate, assedi) possono anch\'essi diventare altri tipi; con la Modalità non sicura, anche i villici evocati dell\'assalto al villaggio (come uomini motosega) e gli assedianti della capanna.", "Una stanza resta"),
    "values-pt": (" Inimigos que um roteiro traz mais tarde (ondas, cercos) também podem virar outros tipos; com o Modo inseguro, também os aldeões invocados do ataque à vila (como homens-serra) e os cercadores da cabana.", "Uma sala fica"),
    "values-ru": (" Враги, которых скрипт приводит позже (волны, осады), тоже могут становиться другими видами; в Небезопасном режиме — и вызванные жители деревни в набеге (как люди с бензопилами), и осаждающие хижину.", "Комната каждый"),
    "values-zh": (" 脚本稍后带来的敌人（增援、围攻）也可以变成其他敌人类型；开启不安全模式后，村庄袭击中被召唤的村民（变成电锯人）和小屋的围攻者也可以。", "在你关闭"),
    "values-ar": (" الأعداء الذين يجلبهم السكربت لاحقًا (موجات، حصارات) يمكنهم أيضًا أن يصبحوا أنواعًا أخرى؛ مع الوضع غير الآمن، كذلك القرويون المستدعَون في هجوم القرية (كرجال المنشار) ومحاصرو الكوخ.", "تبدو الغرفة"),
}

for f, (sentence, marker) in sentences.items():
    p = f"{base}/{f}/strings_ingame.xml"
    s = open(p, encoding="utf-8").read()
    m = re.search(r'(<string name="ingame_cheat_random_enemies_help">)(.*?)(</string>)', s, re.S)
    if not m:
        print(f, "NOT FOUND")
        continue
    body = m.group(2)
    idx = body.find(marker)
    if idx < 0:
        print(f, "marker NOT FOUND")
        continue
    if sentence.strip()[:20] in body:
        print(f, "already inserted")
        continue
    body = body[:idx] + sentence + " " + body[idx:]
    open(p, "w", encoding="utf-8").write(s[: m.start(2)] + body + s[m.end(2):])
    print(f, "inserted")
