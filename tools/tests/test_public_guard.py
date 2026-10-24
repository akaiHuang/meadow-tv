"""Publication guard fixtures are generated in temporary directories, never real credentials."""
import base64
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest
import zipfile

CHECKER = Path(__file__).resolve().parents[1] / "check-public-files.py"
SPEC = importlib.util.spec_from_file_location("public_guard", CHECKER)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PublicGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
        return path

    def run_guard(self, mode="auto"):
        completed = subprocess.run([sys.executable, str(CHECKER), "--root", str(self.root), "--mode", mode, "--json"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        return completed, json.loads(completed.stdout)

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], stderr=subprocess.DEVNULL)

    def test_docs_placeholders_and_environment_references_remain_publishable(self):
        self.write("README.md", "Use an environment variable. Example: " + "s" + "k-REPLACE_WITH_YOUR_KEY\n")
        self.write("client.py", 'import os\ncredential = os.environ["OPENAI_API_KEY"]\n')
        self.write(".env.example", "OPENAI_API_KEY=${OPENAI_API_KEY}\n")
        completed, report = self.run_guard()
        self.assertEqual(0, completed.returncode)
        self.assertTrue(report["complete"])
        self.assertEqual("tree", report["mode"])
        self.assertEqual([], report["findings"])

    def test_generated_token_formats_jwt_and_pem_are_blocked_without_echoing_values(self):
        first = "s" + "k-proj-" + secrets.token_urlsafe(40)
        second = "gh" + "p_" + "".join(secrets.choice("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") for _ in range(40))
        encode = lambda obj: base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()
        jwt = ".".join([encode({"alg": "HS256", "typ": "JWT"}), encode({"sub": "fixture-only", "exp": 9999999999}), secrets.token_urlsafe(32)])
        pem = "-----BEGIN " + "PRIVATE KEY-----\n" + base64.b64encode(bytes([48]) + secrets.token_bytes(95)).decode() + "\n-----END " + "PRIVATE KEY-----\n"
        self.write("config.txt", "\n".join([first, second, jwt, pem]))
        self.write(first + ".txt", "a credential in a filename must also be redacted")
        completed, report = self.run_guard()
        self.assertEqual(1, completed.returncode)
        self.assertTrue(report["complete"])
        self.assertEqual({"openai_token", "github_token", "jwt_credential", "private_key_block", "credential_in_filename"}, {item["type"] for item in report["findings"]})
        for value in [first, second, jwt, pem]:
            self.assertNotIn(value, completed.stdout + completed.stderr)
        self.assertTrue(all(set(item) == {"path", "line", "type"} for item in report["findings"]))

    def test_complete_index_rejects_ignored_tracked_auth_and_staged_token(self):
        self.git("init", "-q")
        self.write(".gitignore", "auth.json\nartifacts/\n")
        self.write("auth.json", "{}")
        self.git("add", "-f", "auth.json")
        token = "s" + "k-" + secrets.token_urlsafe(40)
        self.write("client.txt", token)
        self.git("add", "client.txt", ".gitignore")
        self.write("client.txt", "working copy is clean, but index still contains the generated fixture")
        completed, report = self.run_guard("staged")
        self.assertEqual(1, completed.returncode)
        self.assertEqual("index", report["mode"])
        self.assertTrue(any(item["path"] == "auth.json" and item["type"] == "credential_filename" for item in report["findings"]))
        self.assertTrue(any(item["path"] == "client.txt" and item["type"] == "openai_token" for item in report["findings"]))
        self.assertNotIn(token, completed.stdout + completed.stderr)
        self.write("artifacts/private/notes.txt", "must never publish private task output")
        self.git("add", "-f", "artifacts/private/notes.txt")
        completed, report = self.run_guard("index")
        self.assertEqual(1, completed.returncode)
        self.assertTrue(any(item["type"] == "private_artifact_directory" for item in report["findings"]))

    def test_tree_skips_build_cache_and_private_outputs_but_blocks_auth_names(self):
        for name in ["build/auth.json", ".gradle/credentials.json", "artifacts/private/pairing.json"]:
            self.write(name, "{}");
        for name in ["keys/local.key", ".codex/auth.json", "token", "adbkey", "id_rsa", "release.keystore"]:
            self.write(name, "fixture")
        completed, report = self.run_guard("tree")
        self.assertEqual(1, completed.returncode)
        self.assertTrue(report["complete"])
        found = {item["path"] for item in report["findings"]}
        self.assertEqual({"keys/local.key", ".codex/auth.json", "token", "adbkey", "id_rsa", "release.keystore"}, found)
        self.assertEqual(3, report["excludedTreeDirectories"])

    def test_unscanned_inputs_are_errors_instead_of_a_clean_zero(self):
        self.git("init", "-q")
        self.write("README.md", "not staged yet")
        completed, report = self.run_guard("index")
        self.assertEqual(2, completed.returncode)
        self.assertFalse(report["complete"])
        self.assertEqual("empty_index_use_tree_or_auto", report["errors"][0]["type"])
        (self.root / "external-link").symlink_to(self.root / "README.md")
        completed, report = self.run_guard("tree")
        self.assertEqual(2, completed.returncode)
        self.assertTrue(any(item["type"] == "symlink_not_scanned" for item in report["errors"]))
        guard = MODULE.Guard(self.root, max_file_bytes=2)
        guard.scan_file("README.md")
        self.assertEqual("file_size_limit", guard.errors[0]["type"])

    def test_archive_entries_are_scanned_without_extracting_private_keys(self):
        token = "gh" + "p_" + "".join(secrets.choice("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") for _ in range(40))
        with zipfile.ZipFile(self.root / "bundle.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("config.txt", token)
            archive.writestr("private/identity.pem", "private fixture must not be opened or printed")
        completed, report = self.run_guard("tree")
        self.assertEqual(1, completed.returncode)
        self.assertTrue(report["complete"])
        self.assertTrue(any(item["path"] == "bundle.zip!config.txt" and item["type"] == "github_token" for item in report["findings"]))
        self.assertTrue(any(item["path"] == "bundle.zip!private/identity.pem" and item["type"] == "private_key_or_certificate_filename" for item in report["findings"]))
        self.assertNotIn(token, completed.stdout + completed.stderr)


if __name__ == "__main__":
    unittest.main()
