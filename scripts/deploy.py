"""Deploy the Essential plugin jar to a local Mindustry server folder."""

import argparse
import shutil
import sys
import time
from pathlib import Path

NEW_PERMISSIONS = ("rtv", "votemap")


def add_permissions(permission_file: Path) -> list[str]:
    lines = permission_file.read_text(encoding="utf-8").splitlines(keepends=True)
    report = next((i for i, line in enumerate(lines) if line.strip() == "- report"), None)
    if report is None:
        print(f"no '- report' line in {permission_file}, permissions left alone")
        return []

    indent = lines[report][: len(lines[report]) - len(lines[report].lstrip())]
    ending = "\n" if lines[report].endswith("\n") else ""
    existing = {line.strip().removeprefix("- ") for line in lines if line.strip().startswith("- ")}
    added = [name for name in NEW_PERMISSIONS if name not in existing]
    if not added:
        return []

    if not ending:
        lines[report] += "\n"
    lines[report + 1 : report + 1] = [f"{indent}- {name}\n" for name in added]
    permission_file.write_text("".join(lines), encoding="utf-8")
    return added


def main() -> int:
    parser = argparse.ArgumentParser(description="Deploy Essential-all.jar to a Mindustry server folder.")
    parser.add_argument("--server", required=True, type=Path, help="server folder containing config/mods/")
    parser.add_argument("--jar", type=Path, default=Path("Essential/build/libs/Essential-all.jar"))
    args = parser.parse_args()

    mods = args.server / "config" / "mods"
    if not mods.is_dir():
        print(f"not a server folder: {mods} does not exist", file=sys.stderr)
        return 1
    if not args.jar.is_file():
        print(f"jar not found: {args.jar} (build it with ./gradlew :Essential:shadowJar)", file=sys.stderr)
        return 1

    stamp = time.strftime("%Y%m%d-%H%M%S")
    backup = args.server / f"essentials-backup-{stamp}"
    attempt = 2
    while backup.exists():
        backup = args.server / f"essentials-backup-{stamp}-{attempt}"
        attempt += 1
    backup.mkdir()

    target_jar = mods / args.jar.name
    if target_jar.is_file():
        shutil.copy2(target_jar, backup / target_jar.name)
        print(f"backed up {target_jar} -> {backup / target_jar.name}")

    data = mods / "Essentials"
    if data.is_dir():
        shutil.copytree(data, backup / "Essentials")
        print(f"backed up {data} -> {backup / 'Essentials'}")

    shutil.copy2(args.jar, target_jar)
    print(f"copied {args.jar} -> {target_jar}")

    permission_file = data / "permission.yaml"
    if permission_file.is_file():
        added = add_permissions(permission_file)
        if added:
            print(f"added permissions to the user group: {', '.join(added)}")
        else:
            print("permissions already present, nothing to add")
    else:
        print(f"{permission_file} not found, permissions left alone (the plugin writes it on first start)")

    print(f"done, backup kept in {backup}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
