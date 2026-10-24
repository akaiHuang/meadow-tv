#!/usr/bin/env python3
"""Portable, explicit local setup for Meadow TV's subscription companion."""
import argparse
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE_PROFILE = 2
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in
                         ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))


def private_ipv4(value):
    try:
        address = ipaddress.IPv4Address(value)
    except (ipaddress.AddressValueError, TypeError):
        raise ValueError("A private IPv4 address is required") from None
    if str(address) != value or not any(address in network for network in PRIVATE_NETWORKS):
        raise ValueError("Use a private LAN IPv4 address; loopback and public addresses are not supported")
    return str(address)


def private_directory(path):
    if path.is_symlink():
        raise ValueError("Private directories cannot be symlinks")
    path.mkdir(parents=True, exist_ok=True)
    os.chmod(path, 0o700)
    return path


def state_directory(root):
    return private_directory(private_directory(root / "artifacts" / "private") / "bridge")


def write_private(path, data):
    if path.is_symlink():
        raise ValueError("Private files cannot be symlinks")
    fd, name = tempfile.mkstemp(prefix=".save-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(data)
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def find_tool(name, environ=None):
    env = os.environ if environ is None else environ
    variable = {"codex": "CODEX_BINARY", "adb": "ADB_BINARY", "node": "NODE_BINARY", "openssl": "OPENSSL_BINARY"}.get(name)
    explicit = env.get(variable, "") if variable else ""
    candidates = [explicit] if explicit else []
    if not explicit:
        found = shutil.which(name, path=env.get("PATH"))
        if found:
            candidates.append(found)
        if name == "adb":
            for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
                if env.get(key):
                    candidates.append(str(Path(env[key]) / "platform-tools" / "adb"))
        if name == "codex":
            candidates += ["/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex",
                           "/Applications/Codex.app/Contents/Resources/codex"]
    for candidate in candidates:
        expanded = Path(os.path.expanduser(candidate))
        resolved = str(expanded) if expanded.is_absolute() else shutil.which(candidate, path=env.get("PATH"))
        if resolved and Path(resolved).is_file() and os.access(resolved, os.X_OK):
            return resolved
    raise ValueError((variable + " does not name an executable") if explicit else (name + " was not found in PATH or its supported locations"))


def local_host_for(tv):
    # UDP connect chooses the local route without sending a packet or opening ADB.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
        route.connect((tv, 9))
        return private_ipv4(route.getsockname()[0])


def read_settings(root):
    state = state_directory(root)
    config = json.loads((state / "companion.json").read_text())
    if not isinstance(config, dict):
        raise ValueError("Invalid local companion settings")
    if config.get("certificateProfile") != CERTIFICATE_PROFILE:
        raise ValueError("Run setup to upgrade the local certificate, then pair the TV again")
    config["tv"] = private_ipv4(config["tv"])
    config["host"] = private_ipv4(config["host"])
    if type(config["port"]) is not int or not 1024 <= config["port"] <= 65535:
        raise ValueError("Invalid companion port")
    if not isinstance(config.get("model"), str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", config["model"]):
        raise ValueError("Invalid configured model")
    pairing = json.loads((state / "pairing.json").read_text())
    token = (state / "token").read_text().strip()
    pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((state / "cert.pem").read_text())).hexdigest()
    if len(token) < 32 or not re.fullmatch(r"[A-Za-z0-9_-]+", token):
        raise ValueError("Invalid local pairing token")
    if pairing != {"endpoint": "https://{}:{}".format(config["host"], config["port"]), "token": token, "pin": pin}:
        raise ValueError("Local pairing files do not agree; run setup again")
    if not (state / "key.pem").is_file():
        raise ValueError("Missing local TLS key")
    return state, config, pairing


def certificate_request(host):
    # This local trust anchor is also the pinned TLS server certificate. CA/key
    # signing usage is required by macOS Python/LibreSSL when loaded as a CA file.
    return "[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=server\n[dn]\nCN=Meadow TV Companion\n[server]\nsubjectAltName=IP:{}\nbasicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,digitalSignature,keyEncipherment,keyCertSign,cRLSign\nextendedKeyUsage=serverAuth\n".format(host)


def setup(root, tv, host=None, port=8766, model="gpt-6.1-sol"):
    os.umask(0o077)
    tv = private_ipv4(tv)
    host = private_ipv4(host) if host else local_host_for(tv)
    if host == tv:
        raise ValueError("The Mac host and TV must have different addresses")
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError("Port must be between 1024 and 65535")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", model):
        raise ValueError("Invalid model name")
    state = state_directory(root)
    old = {}
    if (state / "companion.json").exists():
        old = json.loads((state / "companion.json").read_text())
    token = (state / "token").read_text().strip() if (state / "token").exists() else secrets.token_urlsafe(40)
    if len(token) < 32 or not re.fullmatch(r"[A-Za-z0-9_-]+", token):
        raise ValueError("Existing token is invalid; restore the private state before continuing")
    regenerate = old.get("certificateProfile") != CERTIFICATE_PROFILE or old.get("host") != host or not all((state / name).is_file() for name in ("key.pem", "cert.pem"))
    if regenerate:
        openssl = find_tool("openssl")
        with tempfile.TemporaryDirectory(prefix=".tls-", dir=state) as temporary:
            folder = Path(temporary)
            request = folder / "request.cnf"
            request.write_text(certificate_request(host))
            result = subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "365", "-config", str(request), "-keyout", str(folder / "key.pem"), "-out", str(folder / "cert.pem")], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            if result.returncode:
                raise ValueError("OpenSSL could not generate the local TLS certificate")
            for name in ("key.pem", "cert.pem"):
                write_private(state / name, (folder / name).read_text())
    pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((state / "cert.pem").read_text())).hexdigest()
    pairing = {"endpoint": "https://{}:{}".format(host, port), "token": token, "pin": pin}
    config = {"schema": 1, "certificateProfile": CERTIFICATE_PROFILE, "tv": tv, "host": host, "port": port, "model": model}
    for name, data in (("token", token + "\n"), ("pin", pin + "\n"), ("pairing.json", json.dumps(pairing) + "\n"), ("companion.json", json.dumps(config) + "\n")):
        write_private(state / name, data)
    for name in ("key.pem", "cert.pem"):
        os.chmod(state / name, 0o600)
    return {"configured": True, "certificate_created": regenerate, "certificate_profile": CERTIFICATE_PROFILE, "pairing_required": regenerate, "paired_automatically": False}


def pair(root, serial):
    if not re.fullmatch(r"[A-Za-z0-9._:\[\]-]{1,200}", serial):
        raise ValueError("An explicit ADB serial is required")
    _, _, config = read_settings(root)
    adb = find_tool("adb")
    state = subprocess.run([adb, "-s", serial, "get-state"], capture_output=True, text=True, timeout=10)
    if state.returncode or state.stdout.strip() != "device":
        raise ValueError("The selected ADB device is not connected and authorized")
    command = ["am", "broadcast", "-a", "com.akai.livingtv.CONFIGURE_BRIDGE", "-n", "com.akai.livingtv/.BridgeConfigReceiver"]
    for key in ("endpoint", "token", "pin"):
        command += ["--es", key, config[key]]
    receipt = subprocess.run([adb, "-s", serial, "shell"], input=shlex.join(command) + "\n", capture_output=True, text=True, timeout=15)
    if receipt.returncode or not re.search(r"Broadcast completed:\s*result=1\b", receipt.stdout):
        raise ValueError("The TV did not acknowledge pairing; verify that Meadow TV is installed")
    return {"paired": True, "codex_credentials_sent": False}


def subscription_environment():
    # Preserve an explicitly configured official credential-store home only.
    # Desktop thread/session/pipe settings must not bind the companion to this chat.
    return {key: value for key, value in os.environ.items() if key != "OPENAI_API_KEY" and (not key.startswith("CODEX_") or key == "CODEX_HOME")}


def login_method(codex):
    env = subscription_environment()
    result = subprocess.run([codex, "login", "status"], capture_output=True, text=True, timeout=10, env=env)
    message = result.stdout + result.stderr
    if result.returncode:
        return "unavailable"
    return "chatgpt" if "ChatGPT" in message else "other"


def pinned_health(host, port, certificate, pin, token):
    connection = None
    try:
        context = ssl.create_default_context(cafile=str(certificate))
        connection = http.client.HTTPSConnection(host, port, context=context, timeout=2)
        # Authenticate the TLS peer before any Authorization header is sent.
        connection.connect()
        peer = connection.sock.getpeercert(binary_form=True) if connection.sock else None
        if not peer or not secrets.compare_digest(hashlib.sha256(peer).hexdigest(), pin):
            return False
        connection.request("GET", "/health", headers={"Authorization": "Bearer " + token})
        response = connection.getresponse()
        data = response.read(8193)
        if response.status != 200 or len(data) > 8192:
            return False
        payload = json.loads(data)
        return isinstance(payload, dict) and payload.get("ok") is True
    except (OSError, ValueError, ssl.SSLError, http.client.HTTPException):
        return False
    finally:
        if connection is not None:
            connection.close()


def health(root):
    state, config, pairing = read_settings(root)
    return pinned_health(config["host"], config["port"], state / "cert.pem", pairing["pin"], pairing["token"])


def doctor(root):
    report = {"tools": {}}
    paths = {}
    for name in ("codex", "node", "adb", "openssl"):
        try:
            paths[name] = find_tool(name)
            report["tools"][name] = True
        except ValueError:
            report["tools"][name] = False
    try:
        read_settings(root)
        report["configured"] = True
    except (ValueError, OSError, KeyError, TypeError):
        report["configured"] = False
    report["binary_available"] = (root / "artifacts/private/bin/meadow-tv-bridge").is_file()
    try:
        report["login_method"] = login_method(paths["codex"]) if "codex" in paths else "unavailable"
    except (OSError, subprocess.SubprocessError):
        report["login_method"] = "unavailable"
    try:
        report["health"] = health(root) if report["configured"] else False
    except (OSError, ValueError, ssl.SSLError, http.client.HTTPException):
        report["health"] = False
    return report


def start_command(root):
    state, config, _ = read_settings(root)
    binary = root / "artifacts/private/bin/meadow-tv-bridge"
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise ValueError("Download the companion first: python3 tools/download-companion.py")
    for name in ("catalog-seed.json", "catalog-refresh.py"):
        if not (binary.parent / name).is_file():
            raise ValueError("Companion catalog files are missing; download the complete release archive")
    sidecar = root / "bridge/voice/sidecar.mjs"
    if not sidecar.is_file() or not sidecar.with_name("text-sidecar.mjs").is_file() or not sidecar.with_name("codex-client.mjs").is_file():
        raise ValueError("The repository's voice sidecar files are missing")
    codex, node = find_tool("codex"), find_tool("node")
    if login_method(codex) != "chatgpt":
        raise ValueError("Sign in with ChatGPT using your installed Codex before starting")
    args = [str(binary), "-state", str(state), "-listen", "{}:{}".format(config["host"], config["port"]), "-tv", config["tv"], "-codex", codex, "-model", config["model"], "-voice-node", node, "-voice-script", str(sidecar)]
    return args, subscription_environment()


def main(argv=None, root=None):
    os.umask(0o077)
    root = ROOT if root is None else Path(root)
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("setup", help="Create private TLS pairing files; no automatic pairing")
    command.add_argument("--tv", required=True)
    command.add_argument("--host")
    command.add_argument("--port", type=int, default=8766)
    command.add_argument("--model", default="gpt-6.1-sol")
    command = commands.add_parser("pair", help="Explicitly pair one connected ADB device")
    command.add_argument("--serial", required=True)
    commands.add_parser("start", help="Run in this terminal until Ctrl-C")
    commands.add_parser("doctor", help="Print safe tool, login and health status")
    options = parser.parse_args(argv)
    try:
        if options.command == "setup":
            result = setup(root, options.tv, options.host, options.port, options.model)
        elif options.command == "pair":
            result = pair(root, options.serial)
        elif options.command == "doctor":
            result = doctor(root)
        else:
            args, env = start_command(root)
            print("Starting the companion in this terminal. Keep it open; Ctrl-C stops it.", flush=True)
            os.execve(args[0], args, env)
            return 0
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        # Subprocess stderr and request arguments may contain local pairing material.
        print("Companion operation failed: " + (str(error) if isinstance(error, ValueError) else "check local files and required tools"), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
