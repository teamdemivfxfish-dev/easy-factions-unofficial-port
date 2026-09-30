"""Merge a freshly built MineTerritory jar into the Easy Factions release jar.

The shipped download is THREE mods in one jar: the Easy Factions port
(com.jpreiss.easy_factions), the small efwarborn add-on (com.newtl.efwarborn) and this mod
(top.leonx.territory). Dropping a standalone territory jar next to it is a duplicate-modid crash at
mod discovery, so a release is always made by swapping this mod's trees INTO the previous release jar
and bumping all three declared versions together.

This was a hand-run sequence of jar commands for four releases running. It lives here now so the next
release is one command and cannot quietly skip a step.

    python tools/merge_release.py <base release jar> <built territory jar> <version> <output jar>

What it does, and why each part matters:
  * copies every entry of the base jar EXCEPT the territory trees, so the Easy Factions and efwarborn
    classes are carried over as bytes and never rebuilt (there is no source for the EF core)
  * copies the territory trees in fresh from the new build, including data/minecraft tag files
  * rewrites the three version strings in neoforge.mods.toml, written as UTF-8 with NO byte order mark
    (a BOM there makes NeoForge reject the file with "not a valid mod file")
  * prints the class counts, which is the check that the merge produced a whole jar rather than half of
    one, and the last thing to look at before uploading
"""

import io
import re
import sys
import zipfile

TERRITORY_TREES = ("top/leonx/territory/", "assets/territory/", "data/territory/", "data/minecraft/")


def owned_by_territory(name):
    return any(name.startswith(tree) for tree in TERRITORY_TREES)


def bump_toml(text, version):
    """Set every declared mod version to the release version, leaving the rest of the file alone."""
    return re.sub(r'(?m)^version\s*=\s*".*?"$', 'version = "%s"' % version, text)


def merge(base_path, territory_path, version, out_path):
    counts = {"com/jpreiss": 0, "com/newtl/efwarborn": 0, "top/leonx/territory": 0}
    seen = set()

    with zipfile.ZipFile(base_path) as base, zipfile.ZipFile(territory_path) as fresh, \
            zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:

        # the manifest first, as the jar tool would write it
        manifest = base.getinfo("META-INF/MANIFEST.MF")
        out.writestr(manifest, base.read(manifest))
        seen.add(manifest.filename)

        for info in base.infolist():
            if info.filename in seen or owned_by_territory(info.filename):
                continue
            data = base.read(info)
            if info.filename == "META-INF/neoforge.mods.toml":
                text = data.decode("utf-8-sig")
                data = bump_toml(text, version).encode("utf-8")
            out.writestr(info, data)
            seen.add(info.filename)

        for info in fresh.infolist():
            if info.filename.startswith("META-INF/") or info.filename in seen:
                continue
            if not owned_by_territory(info.filename) and not info.is_dir():
                continue
            if info.is_dir() and not owned_by_territory(info.filename):
                continue
            out.writestr(info, fresh.read(info))
            seen.add(info.filename)

    with zipfile.ZipFile(out_path) as done:
        for name in done.namelist():
            if not name.endswith(".class"):
                continue
            for prefix in counts:
                if name.startswith(prefix):
                    counts[prefix] += 1
        toml = done.read("META-INF/neoforge.mods.toml")

    if toml.startswith(b"\xef\xbb\xbf"):
        raise SystemExit("neoforge.mods.toml was written with a BOM; NeoForge will reject this jar")

    print("wrote %s" % out_path)
    print("  easy_factions %d classes" % counts["com/jpreiss"])
    print("  efwarborn     %d classes" % counts["com/newtl/efwarborn"])
    print("  territory     %d classes" % counts["top/leonx/territory"])
    for line in toml.decode("utf-8").splitlines():
        if line.strip().startswith("version") or line.strip().startswith("modId"):
            print("  toml: %s" % line.strip())


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    merge(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
