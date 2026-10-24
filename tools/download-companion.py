#!/usr/bin/env python3
"""Download and verify the published macOS arm64 companion; never start it."""
import argparse
import hashlib
import gzip
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request

from companion import ROOT, private_directory

REPOSITORY = "akaiHuang/meadow-tv"
TAG = "v0.8.16-preview.1"
ASSET = "MeadowTV-companion-macos-arm64.tar.gz"
FILES = {"meadow-tv-bridge", "catalog-seed.json", "catalog-refresh.py"}
MAX_ARCHIVE = 128 * 1024 * 1024
MAX_CONTENT = 256 * 1024 * 1024


def expected_hash(data):
    found = []
    for line in data.decode("utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?(.+)", line.strip())
        if match and match.group(2) == ASSET:
            found.append(match.group(1).lower())
    if len(found) != 1:
        raise ValueError("Release SHA256SUMS must name the companion archive exactly once")
    return found[0]


class PublicRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != "https" or parsed.username or parsed.password or not (parsed.hostname == "github.com" or (parsed.hostname or "").endswith(".githubusercontent.com")):
            raise ValueError("Unexpected release download redirect")
        return super().redirect_request(request, response, code, message, headers, newurl)


def fetch_file(tag, name, destination):
    url = "https://github.com/{}/releases/download/{}/{}".format(REPOSITORY, tag, name)
    limit = MAX_ARCHIVE if name == ASSET else 128 * 1024
    opener = urllib.request.build_opener(PublicRedirect())
    request = urllib.request.Request(url, headers={"User-Agent": "Meadow-TV-companion-setup"})
    with opener.open(request, timeout=30) as response, destination.open("wb") as output:
        total = 0
        while True:
            part = response.read(65536)
            if not part:
                break
            total += len(part)
            if total > limit:
                raise ValueError("Release asset exceeds its size limit")
            output.write(part)


class BoundedTarStream:
    def __init__(self, stream):
        self.stream = stream
        self.remaining = MAX_CONTENT + 1024 * 1024

    def read(self, count=-1):
        data = self.stream.read(min(count if count >= 0 else self.remaining + 1, self.remaining + 1))
        self.remaining -= len(data)
        if self.remaining < 0:
            raise ValueError("Expanded archive metadata or content exceeds its size limit")
        return data


def unpack_verified(archive, checksums, destination):
    if archive.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Release archive exceeds its size limit")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected_hash(checksums):
        raise ValueError("Companion SHA-256 verification failed; nothing was installed")
    destination = private_directory(destination)
    with tempfile.TemporaryDirectory(prefix=".unpack-", dir=destination.parent) as temporary:
        folder = Path(temporary)
        seen, total = set(), 0
        with gzip.open(archive, "rb") as compressed, tarfile.open(fileobj=BoundedTarStream(compressed), mode="r|") as source:
            for member in source:
                if member.name not in FILES or member.name in seen or not member.isfile() or member.sparse or member.size <= 0:
                    raise ValueError("Unexpected path or file type in companion archive")
                total += member.size
                if total > MAX_CONTENT:
                    raise ValueError("Expanded companion exceeds its size limit")
                seen.add(member.name)
                content = source.extractfile(member)
                if content is None:
                    raise ValueError("Unreadable companion archive entry")
                with content, (folder / member.name).open("wb") as output:
                    shutil.copyfileobj(content, output, 65536)
                if (folder / member.name).stat().st_size != member.size:
                    raise ValueError("Truncated companion archive entry")
                os.chmod(folder / member.name, 0o700 if member.name == "meadow-tv-bridge" else 0o600)
        if seen != FILES:
            raise ValueError("Companion archive is missing required files")
        for name in FILES:
            if (destination / name).is_symlink():
                raise ValueError("Companion destinations cannot be symlinks")
        for name in FILES:
            os.replace(folder / name, destination / name)
    return {"downloaded": True, "sha256_verified": True, "started": False}


def download(root, tag=TAG, fetch=None):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", tag):
        raise ValueError("Invalid release tag")
    if platform.system() != "Darwin" or platform.machine().lower() not in ("arm64", "aarch64"):
        raise ValueError("This preview companion is published for macOS arm64 only")
    private = private_directory(root / "artifacts/private")
    with tempfile.TemporaryDirectory(prefix=".download-", dir=private) as temporary:
        folder = Path(temporary)
        if fetch:
            for name in (ASSET, "SHA256SUMS"):
                fetch(tag, name, folder / name)
        else:
            gh = shutil.which("gh")
            result = None
            if gh:
                result = subprocess.run([gh, "release", "download", tag, "--repo", REPOSITORY, "--pattern", ASSET, "--pattern", "SHA256SUMS", "--dir", str(folder)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
            if result is None or result.returncode:
                for name in (ASSET, "SHA256SUMS"):
                    fetch_file(tag, name, folder / name)
        if (folder / "SHA256SUMS").stat().st_size > 128 * 1024:
            raise ValueError("Release checksum file exceeds its size limit")
        return unpack_verified(folder / ASSET, (folder / "SHA256SUMS").read_bytes(), private / "bin")


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default=TAG)
    options = parser.parse_args(argv)
    try:
        print(json.dumps(download(ROOT, options.tag)))
        return 0
    except (ValueError, OSError, tarfile.TarError, subprocess.SubprocessError):
        print("Companion download failed. Check the public release, connection, and SHA256SUMS; no unverified archive is accepted.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
