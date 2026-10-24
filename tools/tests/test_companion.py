"""Portable companion fixtures: no device, network, Codex session or private Core."""
import base64
import hashlib
import http.server
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import shutil
import ssl
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import companion


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


download = load("download_companion_fixture", "download-companion.py")
build = load("build_bridge_fixture", "build-bridge.py")
CERT = "-----BEGIN CERTIFICATE-----\n" + base64.b64encode(b"synthetic certificate fixture only").decode() + "\n-----END CERTIFICATE-----\n"
TOKEN = "fixture_pairing_token_" + "x" * 40


class CompanionTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.old_umask = os.umask(0o077)

    def tearDown(self):
        os.umask(self.old_umask)
        self.folder.cleanup()

    def tls(self, command, **kwargs):
        self.assertEqual(os.umask(0o077), 0o077)
        request = Path(command[command.index("-config") + 1]).read_text()
        self.assertIn("CA:TRUE", request)
        self.assertIn("keyCertSign,cRLSign", request)
        Path(command[command.index("-keyout") + 1]).write_text("synthetic TLS key fixture only")
        Path(command[command.index("-out") + 1]).write_text(CERT)
        return subprocess.CompletedProcess(command, 0, b"", b"")

    def prepared(self):
        with patch.object(companion, "find_tool", return_value="/fixture/openssl"), patch.object(companion.secrets, "token_urlsafe", return_value=TOKEN), patch.object(companion.subprocess, "run", side_effect=self.tls):
            companion.setup(self.root, "10.20.30.40", "10.20.30.2")
        return self.root / "artifacts/private/bridge"

    def archive(self, names=None, special=None):
        file = self.root / download.ASSET
        with tarfile.open(file, "w:gz") as tar:
            for name in names or sorted(download.FILES):
                member = tarfile.TarInfo(name)
                content = ("synthetic " + name).encode()
                member.size = len(content)
                if special and name == special[0]:
                    member.type = special[1]
                    member.linkname = "outside"
                tar.addfile(member, io.BytesIO(content) if member.isfile() else None)
        sums = (hashlib.sha256(file.read_bytes()).hexdigest() + "  " + download.ASSET + "\n").encode()
        return file, sums

    def test_private_ipv4_never_accepts_loopback_or_personal_default(self):
        for value in ("10.20.30.40", "172.16.2.3", "192.168.4.5"):
            self.assertEqual(companion.private_ipv4(value), value)
        for value in ("", "127.0.0.1", "0.0.0.0", "169.254.1.2", "8.8.8.8", "::1", "::ffff:10.20.30.40", "10.20.30.40;echo"):
            with self.assertRaises(ValueError):
                companion.private_ipv4(value)
        with patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            companion.main(["setup"], root=self.root)

    def test_setup_creates_private_idempotent_pairing_without_connecting(self):
        with patch.object(companion, "find_tool", return_value="/fixture/openssl"), patch.object(companion.secrets, "token_urlsafe", return_value=TOKEN), patch.object(companion.subprocess, "run", side_effect=self.tls) as command, patch.object(companion.socket, "socket", side_effect=AssertionError("No network fixture")):
            first = companion.setup(self.root, "10.20.30.40", "10.20.30.2", 9876)
            second = companion.setup(self.root, "10.20.30.40", "10.20.30.2", 9876)
        self.assertTrue(first["certificate_created"])
        self.assertFalse(second["certificate_created"])
        self.assertEqual(command.call_count, 1)
        state, config, pairing = companion.read_settings(self.root)
        self.assertEqual(config["port"], 9876)
        self.assertEqual(config["certificateProfile"], 2)
        legacy = dict(config)
        legacy.pop("certificateProfile")
        (state / "companion.json").write_text(json.dumps(legacy))
        with self.assertRaises(ValueError):
            companion.read_settings(self.root)
        with patch.object(companion, "find_tool", return_value="/fixture/openssl"), patch.object(companion.subprocess, "run", side_effect=self.tls):
            upgraded = companion.setup(self.root, "10.20.30.40", "10.20.30.2", 9876)
        self.assertTrue(upgraded["certificate_created"])
        self.assertTrue(upgraded["pairing_required"])
        self.assertEqual(pairing["token"], TOKEN)
        self.assertEqual(pairing["pin"], hashlib.sha256(ssl_der()).hexdigest())
        self.assertEqual(stat.S_IMODE(state.stat().st_mode), 0o700)
        for file in state.iterdir():
            self.assertEqual(stat.S_IMODE(file.stat().st_mode), 0o600)

    def test_pair_requires_explicit_serial_and_sends_only_local_pairing_over_stdin(self):
        self.prepared()
        calls = []
        def run(command, **kwargs):
            calls.append((command, kwargs))
            return subprocess.CompletedProcess(command, 0, "device\n" if command[-1] == "get-state" else "Broadcast completed: result=1\n", "")
        with patch.object(companion, "find_tool", return_value="/fixture/adb"), patch.object(companion.subprocess, "run", side_effect=run):
            result = companion.pair(self.root, "fixture-usb-serial")
        self.assertTrue(result["paired"])
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[1][0], ["/fixture/adb", "-s", "fixture-usb-serial", "shell"])
        self.assertNotIn(TOKEN, " ".join(calls[1][0]))
        self.assertIn(TOKEN, calls[1][1]["input"])
        self.assertIn(".BridgeConfigReceiver", calls[1][1]["input"])
        self.assertFalse(result["codex_credentials_sent"])
        with self.assertRaises(ValueError):
            companion.pair(self.root, "")

    def test_start_is_foreground_portable_and_strips_api_credentials(self):
        self.prepared()
        folder = self.root / "artifacts/private/bin"
        folder.mkdir()
        for name in download.FILES:
            (folder / name).write_text("synthetic binary or catalog fixture")
        (folder / "meadow-tv-bridge").chmod(0o700)
        voice = self.root / "bridge/voice"
        voice.mkdir(parents=True)
        for name in ("sidecar.mjs", "text-sidecar.mjs", "codex-client.mjs"):
            (voice / name).write_text("// synthetic fixture")
        with patch.object(companion, "find_tool", side_effect=lambda name: "/fixture/" + name), patch.object(companion, "login_method", return_value="chatgpt"), patch.dict(os.environ, {"OPENAI_API_KEY": "synthetic-" + "fixture", "CODEX_API_KEY": "synthetic-" + "fixture", "CODEX_THREAD_ID": "synthetic-thread-fixture", "CODEX_APP_SERVER": "synthetic-pipe-fixture", "CODEX_HOME": "/synthetic/auth-store-fixture"}):
            args, env = companion.start_command(self.root)
        self.assertEqual(args[args.index("-tv") + 1], "10.20.30.40")
        self.assertEqual(args[args.index("-listen") + 1], "10.20.30.2:8766")
        self.assertEqual(args[args.index("-codex") + 1], "/fixture/codex")
        self.assertNotIn("OPENAI_API_KEY", env)
        self.assertNotIn("CODEX_API_KEY", env)
        self.assertNotIn("CODEX_THREAD_ID", env)
        self.assertNotIn("CODEX_APP_SERVER", env)
        self.assertEqual(env["CODEX_HOME"], "/synthetic/auth-store-fixture")
        self.assertNotIn(TOKEN, " ".join(args))

    def test_doctor_outputs_only_safe_status_and_never_pairing_material(self):
        self.prepared()
        with patch.object(companion, "find_tool", side_effect=lambda name: "/fixture/" + name), patch.object(companion, "login_method", return_value="chatgpt"), patch.object(companion, "health", return_value=True):
            report = companion.doctor(self.root)
        serialized = json.dumps(report)
        for secret in (TOKEN, "10.20.30.40", "10.20.30.2", "endpoint", "pin", "cert.pem"):
            self.assertNotIn(secret, serialized)
        self.assertTrue(report["health"])
        self.assertEqual(report["login_method"], "chatgpt")
        self.assertTrue(all(type(value) is bool for value in report["tools"].values()))

    def test_real_loopback_tls_checks_ca_hostname_and_pin_before_authorization(self):
        openssl = shutil.which("openssl")
        if not openssl:
            self.skipTest("OpenSSL is required for the local TLS fixture")
        folder = self.root / "tls-fixture"
        folder.mkdir()
        certificates = []
        for suffix in ("trusted", "untrusted"):
            request, key, certificate = (folder / (suffix + extension) for extension in (".cnf", ".key", ".pem"))
            request.write_text(companion.certificate_request("127.0.0.1"))
            generated = subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-config", str(request), "-keyout", str(key), "-out", str(certificate)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
            self.assertEqual(generated.returncode, 0, "Local TLS fixture certificate generation failed")
            certificates.append((key, certificate))
        requests = []
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append((self.path, self.headers.get("Authorization")))
                body = b'{"ok":true}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(certificates[0][1]), str(certificates[0][0]))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        worker.start()
        certificate = certificates[0][1]
        pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()
        try:
            self.assertTrue(companion.pinned_health("127.0.0.1", server.server_port, certificate, pin, TOKEN))
            self.assertFalse(companion.pinned_health("127.0.0.1", server.server_port, certificates[1][1], pin, TOKEN))
            self.assertFalse(companion.pinned_health("localhost", server.server_port, certificate, pin, TOKEN))
            self.assertFalse(companion.pinned_health("127.0.0.1", server.server_port, certificate, "0" * 64, TOKEN))
            self.assertEqual(requests, [("/health", "Bearer " + TOKEN)], "Failed CA/hostname/pin checks must never send Authorization")
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=2)

    def test_download_verifies_hash_before_extracting_and_never_starts_binary(self):
        archive, sums = self.archive()
        data = archive.read_bytes()
        fetched = []
        def fetch(tag, name, destination):
            fetched.append((tag, name))
            destination.write_bytes(data if name == download.ASSET else sums)
        with patch.object(download.platform, "system", return_value="Darwin"), patch.object(download.platform, "machine", return_value="arm64"):
            result = download.download(self.root, fetch=fetch)
        self.assertTrue(result["sha256_verified"])
        self.assertFalse(result["started"])
        destination = self.root / "artifacts/private/bin"
        self.assertEqual(set(p.name for p in destination.iterdir()), download.FILES)
        self.assertEqual(stat.S_IMODE((destination / "meadow-tv-bridge").stat().st_mode), 0o700)
        before = (destination / "meadow-tv-bridge").read_bytes()
        with self.assertRaises(ValueError):
            download.unpack_verified(archive, ("0" * 64 + "  " + download.ASSET).encode(), destination)
        self.assertEqual((destination / "meadow-tv-bridge").read_bytes(), before)

    def test_archive_rejects_traversal_links_duplicates_and_unknown_files(self):
        unsafe = [(["../meadow-tv-bridge"], None), (["/meadow-tv-bridge"], None), (["meadow-tv-bridge", "meadow-tv-bridge"], None), (["unexpected"], None), (["meadow-tv-bridge"], ("meadow-tv-bridge", tarfile.SYMTYPE)), (["meadow-tv-bridge"], ("meadow-tv-bridge", tarfile.LNKTYPE))]
        for names, special in unsafe:
            archive, sums = self.archive(names, special)
            with self.assertRaises(ValueError):
                download.unpack_verified(archive, sums, self.root / "destination")
        self.assertFalse((self.root / "meadow-tv-bridge").exists())

    def test_build_uses_ignored_stable_link_temporary_modfile_without_vendoring(self):
        module = self.root / "bridge"
        module.mkdir()
        original = "module meadow-tv-bridge\n\ngo 1.25.0\nrequire " + build.MODULE + " v0.0.0-00010101000000-000000000000\n"
        (module / "go.mod").write_text(original)
        (module / "go.sum").write_text("synthetic sum fixture\n")
        (self.root / "app/src/main/assets").mkdir(parents=True)
        (self.root / "app/src/main/assets/official-catalog.json").write_text("synthetic catalog fixture")
        (self.root / "tools").mkdir()
        (self.root / "tools/refresh-public-catalog.py").write_text("# synthetic catalog fixture")
        core = self.root / "private-core-fixture"
        core.mkdir()
        (core / "go.mod").write_text("module " + build.MODULE + "\n")
        def run(command, **kwargs):
            modfile = Path(next(value.split("=", 1)[1] for value in command if value.startswith("-modfile=")))
            self.assertIn('=> "../.local/meadow-core"', modfile.read_text())
            self.assertNotIn(str(core), modfile.read_text())
            self.assertTrue((self.root / ".local/meadow-core").is_symlink())
            self.assertIn("-trimpath", command)
            self.assertIn("-buildvcs=false", command)
            self.assertIn("-ldflags=-s -w", command)
            self.assertEqual(kwargs["env"]["GOARCH"], "arm64")
            self.assertEqual(kwargs["env"]["CGO_ENABLED"], "0")
            Path(command[command.index("-o") + 1]).write_bytes(b"synthetic compiled fixture")
            return subprocess.CompletedProcess(command, 0, b"", b"")
        with patch.object(build.platform, "system", return_value="Darwin"), patch.object(build.platform, "machine", return_value="arm64"), patch.object(build.subprocess, "run", side_effect=run):
            result = build.build(self.root, core, go="/fixture/go")
        self.assertFalse(result["private_core_vendored"])
        self.assertEqual((module / "go.mod").read_text(), original)
        self.assertFalse((self.root / ".local/meadow-core").exists())
        self.assertEqual(list(module.glob(".companion-build-*")), [])
        with self.assertRaises(ValueError):
            build.build(self.root, self.root / "missing-core", go="/fixture/go")

    def test_locator_respects_explicit_tool_before_path_without_using_auth_files(self):
        explicit = self.root / "explicit-codex"
        path = self.root / "codex"
        for candidate in (explicit, path):
            candidate.write_text("#!/bin/sh\nexit 0\n")
            candidate.chmod(0o700)
        env = {"PATH": str(self.root), "CODEX_BINARY": str(explicit)}
        self.assertEqual(companion.find_tool("codex", env), str(explicit))
        self.assertEqual(companion.find_tool("codex", {"PATH": str(self.root)}), str(path))
        with self.assertRaises(ValueError):
            companion.find_tool("codex", {**env, "CODEX_BINARY": str(self.root / "missing")})


def ssl_der():
    import ssl
    return ssl.PEM_cert_to_DER_cert(CERT)


if __name__ == "__main__":
    unittest.main()
