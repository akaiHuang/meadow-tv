#!/usr/bin/env python3
"""Maintainer-only bridge build; private Meadow Core is never copied or vendored."""
import argparse
import json
import os
import platform
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from companion import ROOT, private_directory

MODULE = "github.com/hey-meadow/meadow-code/go"


def build(root, core, go=None):
    os.umask(0o077)
    if platform.system() != "Darwin" or platform.machine().lower() not in ("arm64", "aarch64"):
        raise ValueError("Build the macOS arm64 companion on an arm64 Mac with its developer tools")
    core = Path(core).expanduser().resolve()
    if not core.is_dir() or not (core / "go.mod").is_file():
        raise ValueError("MEADOW_CORE_DIR must name the private Core Go module directory")
    if not re.search(r"(?m)^module\s+" + re.escape(MODULE) + r"\s*$", (core / "go.mod").read_text()):
        raise ValueError("MEADOW_CORE_DIR does not contain the expected Meadow Core module")
    go = go or shutil.which("go")
    if not go:
        raise ValueError("Go is required only for maintainers; normal users should download the companion")
    module = root / "bridge"
    original = (module / "go.mod").read_text()
    if re.search(r"(?m)^replace\s+" + re.escape(MODULE), original):
        raise ValueError("The public go.mod must not contain a private Core replacement")
    output = private_directory(root / "artifacts/private/bin")
    local = private_directory(root / ".local")
    link = local / "meadow-core"
    created_link = not link.exists() and not link.is_symlink()
    if created_link:
        link.symlink_to(core, target_is_directory=True)
    elif not link.is_symlink() or link.resolve() != core:
        raise ValueError("The local Core build link is already in use")
    relative = "../.local/meadow-core"
    descriptor, temporary = tempfile.mkstemp(prefix=".companion-build-", suffix=".mod", dir=module)
    modfile = Path(temporary)
    sumfile = modfile.with_suffix(".sum")
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(original + "\nreplace {} => {}\n".format(MODULE, json.dumps(relative)))
        if (module / "go.sum").is_file():
            shutil.copyfile(module / "go.sum", sumfile)
        env = dict(os.environ)
        env.update(GOOS="darwin", GOARCH="arm64", CGO_ENABLED="0")
        command = [go, "build", "-mod=mod", "-modfile=" + str(modfile), "-trimpath", "-buildvcs=false", "-ldflags=-s -w", "-o", str(output / "meadow-tv-bridge"), "."]
        result = subprocess.run(command, cwd=module, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            raise ValueError("Bridge compilation failed; verify the private Core checkout and Go dependencies")
        os.chmod(output / "meadow-tv-bridge", 0o700)
        assets = {"catalog-seed.json": root / "app/src/main/assets/official-catalog.json", "catalog-refresh.py": root / "tools/refresh-public-catalog.py"}
        for name, source in assets.items():
            shutil.copyfile(source, output / name)
            os.chmod(output / name, 0o600)
    finally:
        modfile.unlink(missing_ok=True)
        sumfile.unlink(missing_ok=True)
        if created_link:
            link.unlink(missing_ok=True)
    return {"built": True, "platform": "macos-arm64", "private_core_vendored": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv)
    core = os.environ.get("MEADOW_CORE_DIR")
    if not core:
        print("Set MEADOW_CORE_DIR to the private Core module directory (the directory containing its go.mod).", file=sys.stderr)
        return 1
    try:
        print(json.dumps(build(ROOT, core)))
        return 0
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(str(error) if isinstance(error, ValueError) else "Bridge build failed; check local paths and tools", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
