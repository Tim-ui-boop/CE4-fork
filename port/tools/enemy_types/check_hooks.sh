#!/usr/bin/env bash
# Syntax-check the port's game-hook units on this phone, with the project's own flags.
#
# The game is 32-bit (GameCube); this host is 64-bit, so a plain host compile reports
# pre-existing errors in include/cManager.h and include/model.h (operator new/delete
# take unsigned int, not size_t). Targeting the game's own width removes them:
#   --target=armv7a-linux-androideabi34
#
# The Termux-derived prefix has no asm/ directory (only asm-generic/), which
# <sys/types.h> reaches; port/whole/cmake/linux-i686.cmake solves the same problem
# with a symlink shim. We do the same in scratch.
#
#   bash .harness/analysis/check_hooks.sh [file.cpp ...]
set -u
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
SHIM="${HARNESS_SCRATCH:-/tmp}/asmshim"
mkdir -p "$SHIM"
[ -e "$SHIM/asm" ] || ln -s "$PREFIX/include/asm-generic" "$SHIM/asm"

FLAGS=(
  -fsyntax-only --target=armv7a-linux-androideabi34
  -fms-extensions -w
  -include port/whole/include/re4_whole_prelude.h
  -std=gnu++11 -Wno-c++11-narrowing -fdelayed-template-parsing
  -fno-strict-aliasing -ffp-contract=off -fwrapv
  -fno-strict-float-cast-overflow -fno-strict-return
  -Wno-address-of-temporary -ferror-limit=0
  "-I$SHIM" "-I$PREFIX/include"
  -Iport/whole/include -Iinclude -Isrc -Iport/whole/runtime
)

FILES=("$@")
if [ ${#FILES[@]} -eq 0 ]; then
  FILES=(port/whole/runtime/enemy_types.cpp port/whole/runtime/enemy_random.cpp)
fi

cd "$REPO" || exit 1
rc=0
for f in "${FILES[@]}"; do
  out=$(clang++ "${FLAGS[@]}" "$f" 2>&1)
  n=$(printf '%s' "$out" | grep -c 'error:' || true)
  if [ "$n" -eq 0 ]; then
    echo "CLEAN  $f"
  else
    echo "ERRORS($n)  $f"
    printf '%s\n' "$out" | grep 'error:' | head -5
    rc=1
  fi
done
exit $rc
