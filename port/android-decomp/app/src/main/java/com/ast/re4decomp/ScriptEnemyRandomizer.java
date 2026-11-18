package com.ast.re4decomp;

/**
 * Script-created enemies: the ones a room's own C++ builds by hand instead of reading them from the
 * chapter's enemy list (ESL). The enemy randomizer's data rewrite cannot reach those: a sidecar list is
 * only read where the game loads a list, and r100's first Ganado is built straight from a literal
 * EM_LIST in r100.cpp, so rewriting emleon00.esl never changed it.
 *
 * That is the encounter before the bingo village, and it is why the randomizer looked like it did
 * nothing at the very start of the game.
 *
 * Port patch 0237 adds the engine hook (runtime/enemy_random.cpp, re4_enemy_random_script_kind), called
 * from EmSetEvent. It keeps the enemy's id, model, position and module -- the s03 cutscene, the
 * script's stored pointer and the death link that advances the story all depend on them -- and changes
 * only the morphed form, EM_LIST.type. For enemy 0x12 the forms em12_set.cpp implements are:
 *
 *   0  the default male villager
 *   1  the young villager
 *   3  the armed villager
 *   4  the Chainsaw Man
 *
 * So the first enemy of the game can be a chainsaw villager at no extra memory cost, which is exactly
 * what the retired play-time randomizer could not promise.
 */
final class ScriptEnemyRandomizer {
  private ScriptEnemyRandomizer() {}

  /** A hand-built enemy the engine hook knows about. */
  static final class Site {
    final int room;
    final int enemyId;
    final String where;

    Site(int room, int enemyId, String where) {
      this.room = room;
      this.enemyId = enemyId;
      this.where = where;
    }
  }

  /** Every site the hook covers, so the page and the log can name them. */
  static final Site[] SITES = {
      // r100.cpp R100Init: `EM_LIST d; d.id = 0x12; d.set = 0x13;` handed to EmSetEvent -- the s03 Ganado
      new Site(0x100, 0x12, "r100, the Ganado at the police car"),
  };

  /** The names of the forms the hook may roll, in EM_LIST.type order (em12_set.cpp). */
  static final String[] FORMS = {"villager", "young villager", "armed villager", "Chainsaw Man"};

  /** The forms' EM_LIST.type values, parallel to {@link #FORMS}. */
  static final int[] FORM_TYPES = {0, 1, 3, 4};

  /** The site for `room`, or null when the room has no hand-built enemy the hook covers. */
  static Site siteOf(int room) {
    for (Site s : SITES) if (s.room == room) return s;
    return null;
  }

  /** The sites as one line, for the diagnostics log and the page's status line. */
  static String describe() {
    StringBuilder out = new StringBuilder();
    for (Site s : SITES) {
      if (out.length() > 0) out.append(", ");
      out.append(String.format(java.util.Locale.ROOT, "r%03x (%s)", s.room, s.where));
    }
    return out.toString();
  }
}
