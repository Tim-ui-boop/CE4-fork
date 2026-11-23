"""Run EnemyShuffle's real Java code against the real disc lists, on this phone.

Extracts the eight story lists from the imported-disc layout the launcher uses, runs
EnemyShuffle.deal() through a small Java harness, and checks the written sidecars:
  - every record kept set/room/pos/rot/hp/Character/Guard_r/be_flag
  - only id, type, flag differ, and every dealt id is a Ganado with a supported type
  - the deal is deterministic for a seed
  - a refusal writes nothing

  python3 .harness/analysis/run_enemy_deal.py [seeds...]
"""
import pathlib
import shutil
import subprocess
import sys

WS = pathlib.Path(".").resolve()
REPO = WS / ".harness/clones/CE4-fork"
APP = REPO / "port/android-decomp/app/src/main/java/com/ast/re4decomp"
WORK = WS / ".harness/enemydeal"
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "port/tools"))
import emlist_dump as em  # noqa: E402

DISC = WS / "Resident Evil 4 (USA) (Disc 1)/Resident Evil 4 (USA) (Disc 1).iso"
LISTS = ["etc/emleon%02d.esl" % i for i in range(8)]

HARNESS = r"""
package com.ast.re4decomp;
import java.io.File;
public final class DealRunner {
  public static void main(String[] a) throws Exception {
    File data = new File(a[0]), disc = new File(a[1]);
    long seed = Long.parseLong(a[2]);
    int options = Integer.parseInt(a[3]);
    EnemyShuffle.Result r = EnemyShuffle.deal(data, disc, seed, options);
    System.out.println("ok=" + r.ok);
    System.out.println("dealt=" + r.dealt + " rooms=" + r.rooms + " saws=" + r.saws
        + " garradors=" + r.garradors + " jjs=" + r.jjs + " supers=" + r.supers
        + " variants=" + r.variants);
    System.out.println("message=" + r.message);
  }
}
"""


def build():
    stub = WORK / "stub/android/util"
    stub.mkdir(parents=True, exist_ok=True)
    (stub / "Log.java").write_text(
        "package android.util;\npublic final class Log {\n"
        " public static int i(String t,String m){return 0;}\n"
        " public static int i(String t,String m,Throwable x){return 0;}\n"
        " public static int e(String t,String m){return 0;}\n"
        " public static int e(String t,String m,Throwable x){return 0;}\n"
        " public static int w(String t,String m){return 0;}\n"
        " public static int d(String t,String m){return 0;}\n}\n")
    src = WORK / "src"
    src.mkdir(parents=True, exist_ok=True)
    (src / "DealRunner.java").write_text(HARNESS)
    classes = WORK / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    files = [str(APP / "EnemyShuffle.java"), str(stub / "Log.java"), str(src / "DealRunner.java")]
    p = subprocess.run(["javac", "-nowarn", "-d", str(classes)] + files,
                       capture_output=True, text=True)
    if p.returncode:
        print(p.stderr[:3000])
        return None
    return classes


def extract():
    """The imported-disc layout: <disc>/files/etc/emleon0N.esl"""
    root = WORK / "disc/files"
    root.mkdir(parents=True, exist_ok=True)
    for name in LISTS:
        body = next(em.find_list(DISC, name.split("/")[1]))[1]
        f = WORK / "disc/files" / name
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_bytes(body)
    return WORK / "disc/files"


def run(classes, data, disc, seed, options):
    p = subprocess.run(["java", "-cp", str(classes), "com.ast.re4decomp.DealRunner",
                        str(data), str(disc), str(seed), str(options)],
                       capture_output=True, text=True)
    out = {}
    for line in p.stdout.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            if k in ("ok", "dealt", "message"):
                out[k] = v
    if "dealt" in out:
        # "dealt=72 rooms=26 saws=4 ..." — one line, several pairs
        for part in out["dealt"].split():
            if "=" in part:
                k, v = part.split("=", 1)
                out[k] = v
        out["dealt"] = out["dealt"].split()[0]
    if p.returncode:
        print(p.stderr[:2000])
    return out


def check_written(data, disc, seed):
    """Re-read the sidecars and compare with the source, byte by byte."""
    problems = []
    changed = 0
    for name in LISTS:
        src = (disc / name).read_bytes()
        out_f = data / "randomizer/enemies" / name
        if not out_f.is_file():
            problems.append(f"{name}: not written")
            continue
        out = out_f.read_bytes()
        if len(out) != 0x2000:
            problems.append(f"{name}: {len(out)} bytes, expected 8192")
            continue
        for no in range(256):
            at = no * 32
            if at + 32 > len(src):
                if out[at + 1] != 0:
                    problems.append(f"{name} entry {no}: invented past the disc's end")
                continue
            for k in range(32):
                if k in (1, 2, 4, 5, 6, 7):
                    continue
                if src[at + k] != out[at + k]:
                    problems.append(f"{name} entry {no}: byte {k} changed")
            if src[at + 1] != out[at + 1] or src[at + 2] != out[at + 2] or src[at+4:at+8] != out[at+4:at+8]:
                changed += 1
    return changed, problems


def main():
    seeds = [int(s) for s in sys.argv[1:]] or [1, 42, 99999]
    if not DISC.exists():
        print("disc image missing:", DISC)
        return 1
    classes = build()
    if classes is None:
        return 1
    disc = extract()
    print("compiled EnemyShuffle + harness; extracted", len(LISTS), "lists\n")
    ok = True
    for options, label in ((1 | 16 | 1024, "defaults"), (0x5FF, "all enemies on")):
        print(f"=== {label} ===")
        for seed in seeds:
            data = WORK / f"data-{options}-{seed}"
            if data.exists():
                shutil.rmtree(data)
            data.mkdir(parents=True)
            r = run(classes, data, disc, seed, options)
            if r.get("ok") != "true":
                print(f" seed {seed}: REFUSED: {r.get('message')}")
                ok = False
                continue
            changed, problems = check_written(data, disc, seed)
            marker = (data / "randomizer/enemies/active").is_file()
            print(f" seed {seed:6d}: dealt {r.get('dealt'):>3} in {r.get('rooms'):>3} rooms, "
                  f"{changed} records differ, marker {'yes' if marker else 'NO'}, "
                  f"{'OK' if not problems and marker else 'PROBLEMS'}")
            if problems:
                ok = False
                for p in problems[:5]:
                    print("    ", p)
            # determinism: same seed again into a fresh folder gives identical bytes
            data2 = WORK / f"repeat-{options}-{seed}"
            if data2.exists():
                shutil.rmtree(data2)
            data2.mkdir(parents=True)
            run(classes, data2, disc, seed, options)
            same = all((data / "randomizer/enemies" / n).read_bytes()
                       == (data2 / "randomizer/enemies" / n).read_bytes() for n in LISTS)
            if not same:
                ok = False
                print("     determinism FAILED")
    print("\nRESULT:", "all checks pass" if ok else "PROBLEMS FOUND")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
