#!/usr/bin/env python3
"""Reject private publication files and recognizable credentials without printing values.

By default, scan the complete Git index, including ignored files added with -f.
An empty/missing index falls back to the file tree. --mode staged is an alias for
the complete index, not just a diff. Tree exclusions never hide indexed files.
Exit 0: checked and clean; 1: blocked content/path; 2: incomplete/error.
This is a publication guard, not proof that arbitrary unknown secrets are absent.
"""
from __future__ import annotations

import argparse
import base64
import collections
import io
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tarfile
import zipfile

MIB = 1024 * 1024
MAX_FILE_BYTES = 64 * MIB
MAX_TOTAL_BYTES = 512 * MIB
MAX_ARCHIVE_ENTRIES = 5000
MAX_ARCHIVE_DEPTH = 2
TREE_SKIP_PARTS = {".git", ".gradle", ".gradle-cache", "gradle-cache", "build", "__pycache__"}
TOKEN_PATTERNS = (
    ("openai_token", re.compile(rb"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,250}\b")),
    ("github_token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,250}|github_pat_[A-Za-z0-9_]{40,250})\b")),
)
JWT = re.compile(rb"\beyJ[A-Za-z0-9_-]{12,4000}\.[A-Za-z0-9_-]{12,12000}\.[A-Za-z0-9_-]{24,1500}\b")
PEM = re.compile(
    rb"-----BEGIN (PRIVATE KEY|RSA PRIVATE KEY|EC PRIVATE KEY|DSA PRIVATE KEY|OPENSSH PRIVATE KEY|ENCRYPTED PRIVATE KEY|CERTIFICATE)-----"
    rb"\s*([A-Za-z0-9+/=\r\n]{64,50000})\s*-----END \1-----"
)


def forbidden_path(name: str) -> str | None:
    parts = PurePosixPath(name.rsplit("!", 1)[-1].replace("\\", "/")).parts
    lower = tuple(part.lower() for part in parts)
    if any(part in {".codex", ".ssh"} for part in lower):
        return "private_auth_directory"
    if any(lower[i:i + 2] == ("artifacts", "private") for i in range(len(lower) - 1)):
        return "private_artifact_directory"
    if not lower:
        return None
    filename = lower[-1]
    if re.match(r"(?:\.?auth(?:\.[\w-]+)?\.json|\.?credentials?(?:\.[\w-]+)?\.json|pairing\.json)(?:\..+)?$", filename):
        return "credential_filename"
    if re.match(r"(?:\.?token|access[_-]token|refresh[_-]token|passwords?|secrets?)(?:\..+)?$", filename):
        return "credential_filename"
    if re.match(r"(?:adbkey|id_(?:rsa|dsa|ecdsa|ed25519)|private[_-]?key)(?:\..+)?$", filename):
        return "private_key_filename"
    if re.search(r"\.(?:pem|key|keystore|jks|p12|pfx|keychain|keychain-db)(?:\..+)?$", filename):
        return "private_key_or_certificate_filename"
    if filename.startswith(".env") and filename not in {".env.example", ".env.sample", ".env.template"}:
        return "environment_credentials_filename"
    return None


def tree_excluded(parts: tuple[str, ...]) -> bool:
    lower = tuple(part.lower() for part in parts)
    return any(part in TREE_SKIP_PARTS for part in lower) or any(
        lower[i:i + 2] == ("artifacts", "private") for i in range(len(lower) - 1)
    )


def entropy(value: bytes) -> float:
    counts = collections.Counter(value)
    return -sum((count / len(value)) * math.log2(count / len(value)) for count in counts.values())


def token_is_placeholder(value: bytes) -> bool:
    # Deliberately narrow: never waive every string containing "test"/"example".
    body = value.split(b"_", 1)[-1] if value.startswith(b"gh") else value
    return len(set(body)) <= 4 or entropy(body) < 3.3 or bool(re.fullmatch(
        rb"(?i)(?:sk-)?(?:your[-_]?|replace[-_]?with[-_]?|redacted[-_]?)[a-z_-]+", value
    ))


def valid_jwt(value: bytes) -> bool:
    try:
        header, payload, signature = value.split(b".")
        decode = lambda part: base64.b64decode(part + b"=" * (-len(part) % 4), altchars=b"-_", validate=True)
        head, body = json.loads(decode(header)), json.loads(decode(payload))
        sig = decode(signature)
        return isinstance(head, dict) and isinstance(body, dict) and isinstance(head.get("alg"), str) and head["alg"].lower() != "none" and len(sig) >= 16 and entropy(signature) >= 3.3
    except (ValueError, UnicodeError, json.JSONDecodeError):
        return False


def safe_path(name: str) -> str:
    """Even a credential used as a filename must not be echoed into CI logs."""
    data = os.fsencode(name)
    for kind, pattern in TOKEN_PATTERNS:
        data = pattern.sub(lambda match: b"[redacted-" + kind.encode() + b"]" if not token_is_placeholder(match.group()) else match.group(), data)
    data = JWT.sub(lambda match: b"[redacted-jwt]" if valid_jwt(match.group()) else match.group(), data)
    return os.fsdecode(data)


class Guard:
    def __init__(self, root: Path, max_file_bytes: int = MAX_FILE_BYTES, max_total_bytes: int = MAX_TOTAL_BYTES):
        self.root = root
        self.max_file_bytes, self.max_total_bytes = max_file_bytes, max_total_bytes
        self.findings: list[dict] = []
        self.errors: list[dict] = []
        self.checked = self.scanned = self.bytes_scanned = self.excluded = 0

    def finding(self, name: str, kind: str, line: int = 1) -> None:
        item = {"path": safe_path(name), "line": line, "type": kind}
        if item not in self.findings:
            self.findings.append(item)

    def error(self, name: str, kind: str) -> None:
        self.errors.append({"path": safe_path(name), "line": 0, "type": kind})

    def reject_path(self, name: str) -> bool:
        self.checked += 1
        if safe_path(name) != name:
            self.finding(name, "credential_in_filename")
            return True
        kind = forbidden_path(name)
        if kind:
            self.finding(name, kind)
        return kind is not None

    def scan_file(self, name: str) -> None:
        if self.reject_path(name):
            return  # Do not open a file already identified as private credentials.
        path = self.root / name
        try:
            info = path.lstat()
            if stat.S_ISLNK(info.st_mode):
                self.error(name, "symlink_not_scanned")
                return
            if not stat.S_ISREG(info.st_mode):
                self.error(name, "non_regular_file_not_scanned")
                return
            if info.st_size > self.max_file_bytes:
                self.error(name, "file_size_limit")
                return
            data = path.read_bytes()
            if len(data) != info.st_size:
                self.error(name, "file_changed_during_scan")
                return
            self.scan_data(name, data)
        except OSError:
            self.error(name, "file_read_failed")

    def scan_data(self, name: str, data: bytes, depth: int = 0) -> None:
        if len(data) > self.max_file_bytes:
            self.error(name, "file_size_limit")
            return
        if self.bytes_scanned + len(data) > self.max_total_bytes:
            self.error(name, "total_scan_size_limit")
            return
        self.scanned += 1
        self.bytes_scanned += len(data)
        for kind, pattern in TOKEN_PATTERNS:
            for match in pattern.finditer(data):
                if not token_is_placeholder(match.group()):
                    self.finding(name, kind, data.count(b"\n", 0, match.start()) + 1)
        for match in JWT.finditer(data):
            if valid_jwt(match.group()):
                self.finding(name, "jwt_credential", data.count(b"\n", 0, match.start()) + 1)
        for match in PEM.finditer(data):
            try:
                body = base64.b64decode(re.sub(rb"\s+", b"", match.group(2)), validate=True)
                if len(body) >= 48:
                    kind = "certificate_block" if match.group(1) == b"CERTIFICATE" else "private_key_block"
                    self.finding(name, kind, data.count(b"\n", 0, match.start()) + 1)
            except ValueError:
                pass
        extension = name.lower().rsplit("!", 1)[-1]
        is_zip = data.startswith((b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")) or extension.endswith((".zip", ".jar", ".apk", ".aar", ".aab", ".whl"))
        is_tar = extension.endswith((".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tar.xz"))
        if is_zip or is_tar:
            if depth >= MAX_ARCHIVE_DEPTH:
                self.error(name, "nested_archive_depth_limit")
            else:
                self.scan_archive(name, data, depth, is_zip)

    def scan_archive(self, name: str, data: bytes, depth: int, is_zip: bool) -> None:
        try:
            if is_zip:
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    members = archive.infolist()
                    if len(members) > MAX_ARCHIVE_ENTRIES:
                        self.error(name, "archive_entry_limit")
                        return
                    for member in members:
                        if member.is_dir():
                            continue
                        child = name + "!" + member.filename
                        if self.reject_path(child):
                            continue
                        if member.file_size > self.max_file_bytes:
                            self.error(child, "archive_entry_size_limit")
                        elif self.bytes_scanned + member.file_size > self.max_total_bytes:
                            self.error(child, "total_scan_size_limit")
                            return
                        elif member.flag_bits & 1:
                            self.error(child, "encrypted_archive_entry_not_scanned")
                        else:
                            self.scan_data(child, archive.read(member), depth + 1)
            else:
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as archive:
                    for index, member in enumerate(archive):
                        if index >= MAX_ARCHIVE_ENTRIES:
                            self.error(name, "archive_entry_limit")
                            return
                        if member.isdir():
                            continue
                        child = name + "!" + member.name
                        if self.reject_path(child):
                            continue
                        if not member.isfile():
                            self.error(child, "archive_link_or_special_file_not_scanned")
                        elif member.size > self.max_file_bytes:
                            self.error(child, "archive_entry_size_limit")
                        elif self.bytes_scanned + member.size > self.max_total_bytes:
                            self.error(child, "total_scan_size_limit")
                            return
                        else:
                            stream = archive.extractfile(member)
                            if stream is None:
                                self.error(child, "archive_entry_read_failed")
                            else:
                                with stream:
                                    self.scan_data(child, stream.read(self.max_file_bytes + 1), depth + 1)
        except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, tarfile.TarError, EOFError):
            self.error(name, "archive_read_failed")

    def tree(self) -> None:
        def walk_error(error: OSError) -> None:
            try:
                name = os.path.relpath(error.filename or self.root, self.root)
            except (TypeError, ValueError):
                name = "."
            self.error(name, "directory_read_failed")
        for base, dirs, files in os.walk(self.root, followlinks=False, onerror=walk_error):
            relative = Path(base).relative_to(self.root)
            kept = []
            for directory in sorted(dirs):
                candidate = relative / directory
                if tree_excluded(candidate.parts):
                    self.excluded += 1
                elif (self.root / candidate).is_symlink():
                    self.error(candidate.as_posix(), "symlink_directory_not_scanned")
                else:
                    kept.append(directory)
            dirs[:] = kept
            for filename in sorted(files):
                self.scan_file((relative / filename).as_posix())

    def git_files(self, entries: list[tuple[str, str, str, str]], mode: str) -> None:
        for permissions, oid, stage, name in entries:
            if stage != "0":
                self.error(name, "unmerged_index_entry")
                continue
            if permissions not in {"100644", "100755"}:
                self.error(name, "git_link_or_submodule_not_scanned")
                continue
            if mode == "tracked":
                self.scan_file(name)
                continue
            if self.reject_path(name):
                continue
            try:
                size = int(git(self.root, "cat-file", "-s", oid).strip())
                if size > self.max_file_bytes:
                    self.error(name, "file_size_limit")
                    continue
                if self.bytes_scanned + size > self.max_total_bytes:
                    self.error(name, "total_scan_size_limit")
                    continue
                payload = git(self.root, "cat-file", "blob", oid)
                if len(payload) != size:
                    self.error(name, "git_blob_size_mismatch")
                else:
                    self.scan_data(name, payload)
            except (ValueError, subprocess.SubprocessError, OSError):
                self.error(name, "git_blob_read_failed")


def git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL, timeout=30)


def index_entries(root: Path) -> list[tuple[str, str, str, str]] | None:
    try:
        top = Path(os.fsdecode(git(root, "rev-parse", "--show-toplevel").strip())).resolve()
        if top != root:
            return None
        raw = git(root, "ls-files", "--stage", "-z")
    except (OSError, subprocess.SubprocessError):
        return None
    entries = []
    for entry in raw.split(b"\0"):
        if entry:
            metadata, name = entry.split(b"\t", 1)
            permissions, oid, stage = metadata.decode("ascii").split()
            entries.append((permissions, oid, stage, os.fsdecode(name)))
    return entries


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--mode", choices=("auto", "index", "staged", "tracked", "tree"), default="auto")
    parser.add_argument("--json", action="store_true", help="Print only metadata; never matched credential values")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    guard = Guard(root)
    mode = args.mode
    if not root.is_dir():
        guard.error(".", "root_directory_missing")
    else:
        try:
            entries = index_entries(root) if mode != "tree" else None
            if mode == "auto":
                mode = "index" if entries or (entries is None and (root / ".git").exists()) else "tree"
            if mode == "staged":
                mode = "index"
            if mode == "tree":
                guard.tree()
            elif entries is None:
                guard.error(".", "git_index_unavailable")
            elif not entries:
                guard.error(".", "empty_index_use_tree_or_auto")
            else:
                guard.git_files(entries, mode)
        except (OSError, ValueError, subprocess.SubprocessError):
            guard.error(".", "scan_failed")
    result = {"mode": mode, "complete": not guard.errors, "filesChecked": guard.checked,
              "payloadsScanned": guard.scanned, "bytesScanned": guard.bytes_scanned,
              "excludedTreeDirectories": guard.excluded, "findings": guard.findings, "errors": guard.errors}
    if args.json:
        print(json.dumps(result, ensure_ascii=True))
    else:
        for issue in guard.findings + guard.errors:
            # JSON quoting makes unusual filenames safe for terminals and CI logs.
            print(f"{json.dumps(issue['path'])}:{issue['line']} {issue['type']}")
        print(f"{mode}: checked={guard.checked} scanned={guard.scanned} findings={len(guard.findings)} errors={len(guard.errors)} complete={not guard.errors}")
    return 2 if guard.errors else 1 if guard.findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
