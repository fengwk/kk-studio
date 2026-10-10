"""Shared fixtures for the Node Daemon installer and packaging tests.

These fixtures never touch the network, never register a real systemd/launchd service and
never download the real 240MB code-server runtime. They build a tiny, self-contained fake
repository plus PATH shims (curl / npm / uname / service managers) that are behaviourally
faithful to the contracts the scripts rely on:

* ``curl`` maps the official URLs onto local fixture files and reports ``--write-out`` for
  ``/latest``.
* ``npm`` honours ``--os`` / ``--cpu`` by materialising only the matching
  ``@koromix/koffi-<os>-<cpu>`` optional prebuilt.
* the daemon entrypoint and the frozen ``lib/node`` are real executables with a controlled
  ``--version`` / ``--check-config`` contract.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import stat
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

DAEMON_VERSION = "1.0.12"
RELEASE_TAG = "v" + DAEMON_VERSION
RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases"
PLATFORMS = ("linux-amd64", "linux-arm64", "macos-amd64", "macos-arm64", "windows-amd64")

_MAIN_JS = f'console.log("kk-studio-daemon {DAEMON_VERSION}")\n'

# The frozen runtime's lib/node is invoked as `lib/node <main.js> --version|--check-config <cfg>`.
_FAKE_NODE = f"""#!/bin/sh
# Frozen runtime Node shim used only by tests.
case "$2" in
  --version) printf 'kk-studio-daemon %s\\n' "${{FAKE_NODE_VERSION:-{DAEMON_VERSION}}}" ;;
  --check-config)
    if [ ! -s "$3" ]; then exit 2; fi
    if grep -q '"invalid"' "$3"; then
      printf 'Invalid daemon configuration: daemon.studioUrl must be an https URL\\n' >&2
      exit 2
    fi
    printf 'Daemon configuration is valid\\n'
    ;;
  *) exit 3 ;;
esac
"""


def write_executable(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def write_text(path: Path, content: str, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    path.chmod(mode)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    digest.update(path.read_bytes())
    return digest.hexdigest()


def code_server_tar(path: Path) -> None:
    """Build a minimal stand-in for the code-server standalone archive."""
    work = path.parent / "cs-src"
    root = work / "code-server-4.141.0-linux-amd64"
    write_executable(root / "lib" / "node", _FAKE_NODE)
    with tarfile.open(path, "w:gz") as archive:
        archive.add(root, arcname="code-server-4.141.0-linux-amd64")
    shutil.rmtree(work)


def _curl_shim() -> str:
    return r"""#!/usr/bin/env python3
import os
import shutil
import sys

args = sys.argv[1:]
url = None
output = None
write_out = False
i = 0
while i < len(args):
    arg = args[i]
    if arg == "--output":
        i += 1
        output = args[i]
    elif arg == "--write-out":
        i += 1
        write_out = True
    elif arg.startswith("--output="):
        output = arg.split("=", 1)[1]
    elif not arg.startswith("-"):
        url = arg
    i += 1

base = os.environ["FAKE_RELEASE_BASE"]
if url is None:
    sys.exit(22)
if url == base + "/latest":
    sys.stdout.write(base + "/tag/" + os.environ["FAKE_TAG"])
    sys.exit(0)
if write_out:
    sys.exit(22)
name = url.rsplit("/", 1)[-1]
src = os.path.join(os.environ["FAKE_HTTP_DIR"], name)
if not os.path.isfile(src) or output is None:
    sys.stderr.write("fake curl: no fixture for %s\n" % url)
    sys.exit(22)
shutil.copyfile(src, output)
"""


def _npm_shim() -> str:
    return r"""#!/usr/bin/env python3
import os
import sys

args = sys.argv[1:]
prefix = None
target_os = None
target_cpu = None
i = 0
while i < len(args):
    arg = args[i]
    if arg == "--prefix":
        i += 1
        prefix = args[i]
    elif arg.startswith("--prefix="):
        prefix = arg.split("=", 1)[1]
    elif arg.startswith("--os="):
        target_os = arg.split("=", 1)[1]
    elif arg.startswith("--cpu="):
        target_cpu = arg.split("=", 1)[1]
    i += 1

if prefix is None:
    sys.exit(1)
os.makedirs(os.path.join(prefix, "node_modules"), exist_ok=True)
if os.environ.get("FAKE_NPM_OMIT_PREBUILT") == "1" or target_os is None or target_cpu is None:
    sys.exit(0)
prebuilt = os.path.join(prefix, "node_modules", "@koromix", "koffi-%s-%s" % (target_os, target_cpu))
os.makedirs(prebuilt, exist_ok=True)
with open(os.path.join(prebuilt, "index.js"), "w", encoding="utf-8") as handle:
    handle.write("module.exports = {}\n")
"""


class ReleaseFixture:
    """A fake repository root for ``scripts/daemon/prepare-release.sh``."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.script = _scripts_dir() / "prepare-release.sh"
        self.bindir = root / "bin"
        self.http_dir = root / "http"
        self.repo = root / "repo"
        self.bindir.mkdir(parents=True, exist_ok=True)
        self.http_dir.mkdir(parents=True, exist_ok=True)

        write_executable(self.bindir / "curl", _curl_shim())
        write_executable(self.bindir / "npm", _npm_shim())

        daemon = self.repo / "daemon"
        write_text(self.repo / "LICENSE", "Apache License 2.0\n", 0o644)
        write_text(daemon / "THIRD_PARTY_NOTICES", "third-party notices\n", 0o644)
        write_text(daemon / "package.json", f'{{"name":"@kk-studio/daemon","version":"{DAEMON_VERSION}"}}\n', 0o644)
        write_text(daemon / "package-lock.json", "{}\n", 0o644)
        write_text(daemon / "dist" / "main.js", _MAIN_JS, 0o644)
        write_executable(daemon / "ide-extension" / "extension.js", "module.exports = {}\n")

        runtime_tar = self.http_dir / "code-server.tar.gz"
        code_server_tar(runtime_tar)
        digest = sha256_of(runtime_tar)
        code_server = {}
        for platform in PLATFORMS:
            name = f"code-server-4.141.0-{platform}.tar.gz"
            shutil.copyfile(runtime_tar, self.http_dir / name)
            code_server[platform] = {
                "url": f"https://example.invalid/{name}",
                "sha256": digest,
            }
        import json

        write_text(
            daemon / "runtime-manifest.json",
            json.dumps({"schemaVersion": 1, "codeServerVersion": "4.141.0", "codeServer": code_server}, indent=2)
            + "\n",
            0o644,
        )
        self._git_init()

    def _git_init(self) -> None:
        env = {
            **os.environ,
            "GIT_AUTHOR_NAME": "fixture",
            "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
            "GIT_COMMITTER_NAME": "fixture",
            "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
        }
        for command in (
            ["git", "init", "-q", "-b", "main"],
            ["git", "add", "-A"],
            ["git", "commit", "-q", "-m", "fixture"],
        ):
            subprocess.run(command, cwd=self.repo, env=env, check=True, capture_output=True)

    def run(self, *args: str, extra_env: dict | None = None) -> subprocess.CompletedProcess:
        env = {
            **os.environ,
            "PATH": f"{self.bindir}:{os.environ['PATH']}",
            "KK_STUDIO_REPO_ROOT": str(self.repo),
            "FAKE_RELEASE_BASE": RELEASE_BASE,
            "FAKE_TAG": RELEASE_TAG,
            "FAKE_HTTP_DIR": str(self.http_dir),
            "NODE_BIN": shutil.which("node") or "node",
            "NPM_BIN": str(self.bindir / "npm"),
            **(extra_env or {}),
        }
        return subprocess.run(
            ["bash", str(self.script), *args],
            cwd=self.repo,
            env=env,
            capture_output=True,
            text=True,
        )

    def release_dir(self, platform: str) -> Path:
        return self.repo / "daemon" / "release" / platform


def _scripts_dir() -> Path:
    return Path(__file__).resolve().parent.parent


class InstallFixture:
    """A fake host for ``scripts/daemon/install.sh``."""

    def __init__(self, root: Path, host_os: str = "Linux", machine: str = "x86_64") -> None:
        self.root = root
        self.script = _scripts_dir() / "install.sh"
        self.host_os = host_os
        self.machine = machine
        self.bindir = root / "bin"
        self.http_dir = root / "http"
        self.home = root / "home"
        self.state = root / "state"
        self.bindir.mkdir(parents=True, exist_ok=True)
        self.http_dir.mkdir(parents=True, exist_ok=True)
        self.home.mkdir(parents=True, exist_ok=True)
        self.state.mkdir(parents=True, exist_ok=True)

        write_executable(self.bindir / "curl", _curl_shim())
        write_executable(self.bindir / "uname", self._uname_shim())
        if host_os == "Linux":
            write_executable(self.bindir / "systemctl", self._systemctl_shim())
        else:
            write_executable(self.bindir / "launchctl", self._launchctl_shim())
            write_executable(self.bindir / "plutil", self._plutil_shim())

        self.asset = f"kk-studio-daemon-{RELEASE_TAG}-{self.release_os}-{self.release_arch}.tar.gz"
        archive = self.http_dir / self.asset
        self._build_archive(archive)
        write_text(self.http_dir / (self.asset + ".sha256"), f"{sha256_of(archive)}  {self.asset}\n", 0o644)

    @property
    def release_os(self) -> str:
        return "linux" if self.host_os == "Linux" else "macos"

    @property
    def release_arch(self) -> str:
        return "amd64" if self.machine in ("x86_64", "amd64") else "arm64"

    def _build_archive(self, path: Path) -> None:
        work = self.root / "archive-src"
        write_executable(work / "runtime" / "code-server" / "lib" / "node", _FAKE_NODE)
        write_text(work / "main.js", _MAIN_JS, 0o644)
        write_text(work / "runtime-manifest.json", f'{{"schemaVersion":1,"daemonVersion":"{DAEMON_VERSION}"}}\n', 0o644)
        with tarfile.open(path, "w:gz") as archive:
            for item in sorted(work.rglob("*")):
                archive.add(item, arcname=str(item.relative_to(work)))
        shutil.rmtree(work)

    def _uname_shim(self) -> str:
        return f"""#!/bin/sh
case "$1" in
  -s) printf '{self.host_os}\\n' ;;
  -m) printf '{self.machine}\\n' ;;
  *) uname "$@" ;;
esac
"""

    def _systemctl_shim(self) -> str:
        return r"""#!/bin/sh
state="$FAKE_SERVICE_STATE"
case "$*" in
  *"show-environment"*) exit 0 ;;
  *"show --property=FragmentPath"*)
    if [ -f "$state/unit.path" ]; then cat "$state/unit.path"; fi
    exit 0
    ;;
  *"is-active --quiet"*)
    [ -f "$state/active" ] && exit 0 || exit 3
    ;;
  *"restart"* | *"start"*)
    : > "$state/active"
    exit 0
    ;;
  *"stop"* | *"disable"*)
    rm -f "$state/active"
    exit 0
    ;;
  *"status"*)
    [ -f "$state/active" ] && exit 0 || exit 3
    ;;
  *) exit 0 ;;
esac
"""

    def _launchctl_shim(self) -> str:
        return r"""#!/bin/sh
state="$FAKE_SERVICE_STATE"
case "$1" in
  print)
    last=""
    for target in "$@"; do last="$target"; done
    case "$last" in
      gui/*/*) [ -f "$state/active" ] && exit 0 || exit 1 ;;
      gui/*) exit 0 ;;
      *) exit 0 ;;
    esac
    ;;
  bootout)
    rm -f "$state/active"
    exit 0
    ;;
  bootstrap)
    : > "$state/active"
    exit 0
    ;;
  kickstart)
    printf '%s\n' "$FAKE_KICKSTART_PID"
    exit 0
    ;;
  *) exit 0 ;;
esac
"""

    def _plutil_shim(self) -> str:
        return r"""#!/bin/sh
# plutil -lint <plist>: accept well-formed enough plists, reject garbage.
if [ "$1" = "-lint" ]; then
  if head -n 1 "$2" | grep -q '^<'; then exit 0; fi
  exit 1
fi
exit 0
"""

    def make_inputs(self, config: str = '{"daemon":{"studioUrl":"https://studio.example.com"}}', token: str = "token-value") -> tuple[Path, Path]:
        directory = self.root / "inputs"
        directory.mkdir(mode=0o700, exist_ok=True)
        config_file = directory / "daemon.json"
        token_file = directory / "daemon.token"
        write_text(config_file, config, 0o600)
        write_text(token_file, token, 0o600)
        return config_file, token_file

    def run(self, *args: str, extra_env: dict | None = None, record_service: bool = True) -> subprocess.CompletedProcess:
        env = {
            **os.environ,
            "PATH": f"{self.bindir}:{os.environ['PATH']}",
            "HOME": str(self.home),
            "FAKE_RELEASE_BASE": RELEASE_BASE,
            "FAKE_TAG": RELEASE_TAG,
            "FAKE_HTTP_DIR": str(self.http_dir),
            "FAKE_SERVICE_STATE": str(self.state),
            "FAKE_KICKSTART_PID": str(os.getpid()),
            "DAEMON_VERIFY_TIMEOUT_SECONDS": "1",
            "DAEMON_VERIFY_STABLE_SECONDS": "0",
            "TMPDIR": str(self.root / "tmp"),
            **(extra_env or {}),
        }
        (self.root / "tmp").mkdir(mode=0o700, exist_ok=True)
        return subprocess.run(
            ["bash", str(self.script), *args],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
        )

    @property
    def install_root(self) -> Path:
        return self.home / ".kk-studio"


class TempDirTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
