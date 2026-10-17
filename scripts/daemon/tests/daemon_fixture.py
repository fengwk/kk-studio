"""Shared fixtures for the Node Daemon installer and packaging tests.

These fixtures never touch the network, never register a real systemd/launchd service and
never download the real 240MB code-server runtime. They build a tiny, self-contained fake
repository plus PATH shims (curl / npm / uname / service managers) that are behaviourally
faithful to the contracts the scripts rely on:

* ``curl`` maps the official URLs onto local fixture files and reports ``--write-out`` for
  ``/latest``.
* ``npm`` honours ``--os`` / ``--cpu`` by materialising only the matching
  ``@koromix/koffi-<os>-<cpu>`` optional prebuilt, and — like the real koffi install script,
  which cannot load a foreign prebuilt on this host and would fall back to a source build —
  refuses any install that would run target lifecycle scripts without ``--ignore-scripts``.
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

DAEMON_VERSION = "1.0.14"
CODE_SERVER_VERSION = "4.141.0-kk.1"
RELEASE_TAG = "v" + DAEMON_VERSION
RELEASE_BASE = "https://github.com/fengwk/kk-studio/releases"
PLATFORMS = ("linux-amd64", "linux-arm64", "macos-amd64", "macos-arm64", "windows-amd64")

_MAIN_JS = f'console.log("kk-studio-daemon {DAEMON_VERSION}")\n'

# The frozen runtime's lib/node is invoked as `lib/node -e ...` or
# `lib/node <main.js> --version|--check-config <cfg>`.
_FAKE_NODE = f"""#!/bin/sh
# Frozen runtime Node shim used only by tests.
if [ "$1" = -e ]; then
  # 安装器调用是 `node -e <script> <manifest> <platform>`。
  if grep -q "\\"$4\\"" "$3"; then printf '%s' "$4"; exit 0; fi
  exit 2
fi
case "$2" in
  --version) printf 'kk-studio-daemon %s\\n' "${{FAKE_NODE_VERSION:-{DAEMON_VERSION}}}" ;;
  --check-config)
    if [ ! -s "$3" ]; then exit 2; fi
    # 真实 daemon.json 是裸配置对象；这里同样拒绝非法取值与外层 "daemon" 包裹的假结构，
    # 以免 fixture 接受真实入口会拒绝的配置而假绿。
    if grep -q '"invalid"' "$3" || grep -q '"daemon"[[:space:]]*:' "$3"; then
      printf 'Invalid daemon configuration: daemon.daemon: unknown field\\n' >&2
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


def code_server_tar(path: Path, platform: str) -> None:
    """Build a minimal stand-in for one frozen fork code-server archive."""
    work = path.parent / f"cs-src-{platform}"
    node_name = "node.exe" if platform.startswith("windows-") else "node"
    root = work / f"code-server-{CODE_SERVER_VERSION}-{platform}"
    write_executable(root / "lib" / node_name, _FAKE_NODE)
    write_text(root / "out" / "node" / "entry.js", "// frozen entry\n", 0o644)
    bin_dir = root / "bin"
    bin_dir.mkdir()
    (bin_dir / "code-server").symlink_to(Path("..") / "out" / "node" / "entry.js")
    with tarfile.open(path, "w:gz") as archive:
        archive.add(root, arcname=root.name)
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
    # The archive validator uses the real parser; only npm selection and native payloads are fixtures.
    parser = Path(__file__).resolve().parents[3] / "daemon" / "node_modules" / "tar"
    return "#!/usr/bin/env python3\nTAR_PARSER = " + repr(str(parser)) + "\n" + r"""
import json
import os
import sys

args = sys.argv[1:]
prefix = None
target_os = None
target_cpu = None
ignore_scripts = False
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
    elif arg == "--ignore-scripts":
        ignore_scripts = True
    i += 1

log = os.environ.get("FAKE_NPM_LOG")
if log:
    with open(log, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(args) + "\n")

if prefix is None:
    sys.exit(1)
# Real npm runs the target package's lifecycle scripts here. koffi's install script loads the
# target prebuilt in the host process and falls back to a source build; cross-platform staging
# only ever ships the locked prebuilt, so an install without --ignore-scripts must fail.
if not ignore_scripts:
    sys.stderr.write("fake npm: refusing to run target lifecycle scripts without --ignore-scripts\n")
    sys.stderr.write(
        "fake npm: koffi install script cannot load @koromix/koffi-%s-%s on this host and failed to rebuild from source\n"
        % (target_os, target_cpu)
    )
    sys.exit(1)
os.makedirs(os.path.join(prefix, "node_modules"), exist_ok=True)
os.symlink(TAR_PARSER, os.path.join(prefix, "node_modules", "tar"), target_is_directory=True)
if os.environ.get("FAKE_NPM_OMIT_PREBUILT") == "1" or target_os is None or target_cpu is None:
    sys.exit(0)
prebuilt = os.path.join(prefix, "node_modules", "@koromix", "koffi-%s-%s" % (target_os, target_cpu))
os.makedirs(prebuilt, exist_ok=True)
with open(os.path.join(prebuilt, "index.js"), "w", encoding="utf-8") as handle:
    handle.write("module.exports = {}\n")
if os.environ.get("FAKE_NPM_FOREIGN_PREBUILT") == "1":
    foreign = os.path.join(prefix, "node_modules", "@koromix", "koffi-freebsd-x64")
    os.makedirs(foreign, exist_ok=True)
    with open(os.path.join(foreign, "index.js"), "w", encoding="utf-8") as handle:
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
        # 每次 npm 调用的原始参数追加写入这里，供测试断言选择器与 --ignore-scripts。
        self.npm_log = root / "npm-args.log"
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

        code_server = {}
        for platform in PLATFORMS:
            name = f"code-server-{CODE_SERVER_VERSION}-{platform}.tar.gz"
            runtime_tar = self.http_dir / name
            code_server_tar(runtime_tar, platform)
            code_server[platform] = {
                "url": f"https://github.com/fengwk/code-server/releases/download/v{CODE_SERVER_VERSION}/{name}",
                "sha256": sha256_of(runtime_tar),
            }
        import json

        write_text(
            daemon / "runtime-manifest.json",
            json.dumps({"schemaVersion": 1, "codeServerVersion": CODE_SERVER_VERSION, "codeServer": code_server}, indent=2)
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
            "FAKE_NPM_LOG": str(self.npm_log),
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

    def _build_archive(self, path: Path, platform: str | None = None) -> None:
        work = self.root / "archive-src"
        write_executable(work / "runtime" / "code-server" / "lib" / "node", _FAKE_NODE)
        write_text(work / "runtime" / "code-server" / "out" / "node" / "entry.js", "// frozen entry\n", 0o644)
        write_text(work / "main.js", _MAIN_JS, 0o644)
        key = platform or f"{self.release_os}-{self.release_arch}"
        write_text(
            work / "runtime-manifest.json",
            f'{{"schemaVersion":1,"daemonVersion":"{DAEMON_VERSION}","codeServer":{{"{key}":{{"url":"https://example.invalid"}}}}}}\n',
            0o644,
        )
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

    def make_inputs(self, config: str = '{"studioUrl":"https://studio.example.com"}', token: str = "token-value") -> tuple[Path, Path]:
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
