"""Pack a Magisk module directory into a flashable ZIP, without Info-ZIP.

`build.sh` normally shells out to `zip`, which Git Bash for Windows does not
ship.  This helper writes the same archive shape, so a flash sees what an
Info-ZIP build produces:

  * paths relative to the module root, no wrapper directory, so Magisk finds
    META-INF/com/google/android/update-binary at the top level;
  * directory entries, and dotfiles - `system/priv-app/PermissionController/
    .replace` is an empty file whose whole purpose is being a name, and a
    packager that skips hidden files silently loses the cold-boot audio fix;
  * unix mode bits in external_attr, which is what `unzip -l` and `unzip -Z`
    report: 0755 for the scripts and the ALSA tools Magisk has to execute, 0644
    for everything else.  magisk/install.sh re-permissions the tree anyway, so
    this is about the archive being readable and reviewable, not about the
    install working.

Timestamps are fixed to the DOS epoch, so packing the same tree twice gives
byte-comparable archives and a diff between two module ZIPs means something.

Usage: python3 tools/mkzip.py <out.zip> [module-dir]      (module-dir default: .)
"""
import os
import sys
import zipfile

# Names that must carry the executable bit.  Everything ending in .sh is
# included by rule: install.sh, service.sh and post-fs-data.sh are all run by
# the flasher or by init, and none of them is executable in a Windows checkout.
EXEC_NAMES = {
    "update-binary",
    "tinymix",
    "tinymix32",
    "tinypcm",
    "tinypcm32",
    "tinycap",
}

SKIP_NAMES = {".DS_Store", "__MACOSX"}

# Fixed DOS-epoch timestamp: 1980-01-01 is the earliest date the format stores.
STAMP = (1980, 1, 1, 0, 0, 0)


def mode_for(name):
    """Unix mode for one file name, as recorded in external_attr."""
    if name in EXEC_NAMES or name.endswith(".sh"):
        return 0o100755
    return 0o100644


def pack(out_path, root):
    out_abs = os.path.abspath(out_path)
    root = os.path.abspath(root)
    if not os.path.isdir(root):
        sys.stderr.write("mkzip.py: not a directory: %s\n" % root)
        return 2
    count = 0
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = sorted(d for d in dirnames if d not in SKIP_NAMES)
            rel_dir = os.path.relpath(dirpath, root).replace(os.sep, "/")
            if rel_dir == ".":
                rel_dir = ""
            for name in sorted(filenames):
                if name in SKIP_NAMES:
                    continue
                full = os.path.join(dirpath, name)
                if os.path.abspath(full) == out_abs:
                    # Never fold the archive being written into itself.
                    continue
                arc = rel_dir + ("/" if rel_dir else "") + name
                info = zipfile.ZipInfo(arc, date_time=STAMP)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = mode_for(name) << 16
                info.create_system = 3          # 3 = unix, so the mode is honoured
                with open(full, "rb") as handle:
                    zf.writestr(info, handle.read())
                count += 1
            if rel_dir:
                info = zipfile.ZipInfo(rel_dir + "/", date_time=STAMP)
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o040755 << 16
                info.create_system = 3
                zf.writestr(info, b"")
    sys.stdout.write("entries=%d  out=%s\n" % (count, out_path))
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.stderr.write(__doc__)
        sys.exit(2)
    sys.exit(pack(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "."))
