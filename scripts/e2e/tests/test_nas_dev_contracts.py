"""Permanent guards for the NAS dev node image, its reload command and the publish workflow."""

import base64
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

from test_build_scripts import function_body


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
DEV_ROOT = REPOSITORY_ROOT / "deploy" / "dev"
DEV_DOCKERFILE = DEV_ROOT / "Dockerfile"
DEV_ENTRYPOINT = DEV_ROOT / "entrypoint.sh"
DEV_RELOAD = DEV_ROOT / "reload.sh"
DEV_HEALTHCHECK = DEV_ROOT / "healthcheck.sh"
DEV_KNOWN_HOSTS = DEV_ROOT / "ssh_known_hosts"
DEV_SSH_CONFIG = DEV_ROOT / "ssh_config"
DEV_ASKPASS = DEV_ROOT / "git-askpass.sh"
DEV_SCRIPT = REPOSITORY_ROOT / "scripts" / "dev.sh"
DOCKERIGNORE = REPOSITORY_ROOT / ".dockerignore"
PUBLISH_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "docker-publish.yml"
DEPLOYMENT_DOC = REPOSITORY_ROOT / "docs" / "operations" / "deployment.md"
DEVELOPMENT_DOC = REPOSITORY_ROOT / "docs" / "operations" / "development-and-testing.md"
WEB_APPLICATION_CONFIG = REPOSITORY_ROOT / "web" / "src" / "main" / "resources" / "application.yml"
DEV_SHELL_SCRIPTS = (DEV_ENTRYPOINT, DEV_RELOAD, DEV_HEALTHCHECK)

REQUIRED_APT_PACKAGES = {
    "bash",
    "ca-certificates",
    "curl",
    "ffmpeg",
    "git",
    "jq",
    "lsof",
    "openssh-client",
    "python3",
}

GITHUB_CLI_DEB_URL = "github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_amd64.deb"
GITHUB_CLI_DEB_SHA256 = "698c8d88cc19cc92bfe96bad58d10b2a5b274c52433d6dc57799c81f6139d5fc"
# GitHub's published github.com host key fingerprints; the image must carry exactly the
# matching key material, which this test re-derives from the pinned key blobs.
GITHUB_SSH_KEY_FINGERPRINTS = {
    "ssh-ed25519": "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
    "ecdsa-sha2-nistp256": "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
    "ssh-rsa": "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s",
}

# The Dev node only receives the HTTP(S) Main origin; the Daemon gateway URI (ws/wss plus
# this fixed path) is derived inside the container.
DAEMON_GATEWAY_PATH = "/api/harness/environment-daemon/v1"
CONTROL_PLANE_BASE_URL = "http://vps-kk-studio:8080"
DAEMON_GATEWAY_URI = f"ws://vps-kk-studio:8080{DAEMON_GATEWAY_PATH}"
# One host is shared by every rejected origin so "the entrypoint must not echo the supplied
# value" can be asserted with a single marker.
REJECTED_ORIGIN_MARKER = "daemon.invalid"
REJECTED_ORIGINS = (
    "",
    f"{REJECTED_ORIGIN_MARKER}:8080",
    f"ws://{REJECTED_ORIGIN_MARKER}:8080",
    f"wss://{REJECTED_ORIGIN_MARKER}:8080",
    f"ftp://{REJECTED_ORIGIN_MARKER}:8080",
    f"http://{REJECTED_ORIGIN_MARKER}:8080{DAEMON_GATEWAY_PATH}",
    f"http://{REJECTED_ORIGIN_MARKER}:8080/?probe=1",
    f"http://{REJECTED_ORIGIN_MARKER}:8080#fragment",
    f"http://user:secret@{REJECTED_ORIGIN_MARKER}:8080",
    f"http://{REJECTED_ORIGIN_MARKER}:8080 ",
    f"http://:{REJECTED_ORIGIN_MARKER}",
    f"http://-{REJECTED_ORIGIN_MARKER}",
    f"http://{REJECTED_ORIGIN_MARKER}:invalid",
    f"http://{REJECTED_ORIGIN_MARKER}:0",
    f"http://{REJECTED_ORIGIN_MARKER}:65536",
    "http://",
)
# Environment variables the entrypoint reads; a sourced entrypoint must observe only what a
# test sets explicitly instead of the developer's own shell.
ENTRYPOINT_ENV_NAMES = (
    "KK_STUDIO_CONTROL_PLANE_BASE_URL",
    "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED",
    "KK_STUDIO_DAEMON_REGISTRATION_TOKEN",
    "KK_STUDIO_REPOSITORY_DIR",
    "KK_STUDIO_GIT_BRANCH",
    "SPRING_PROFILES_ACTIVE",
    "SPRING_FLYWAY_ENABLED",
)
# Git must never read the developer's own identity/configuration when a test builds a
# throwaway repository, and a global `commit.gpgsign` would break every fixture commit.
GIT_FIXTURE_ENV = {
    "GIT_CONFIG_GLOBAL": "/dev/null",
    "GIT_CONFIG_SYSTEM": "/dev/null",
    "GIT_AUTHOR_NAME": "kk-studio test",
    "GIT_AUTHOR_EMAIL": "test@kk-studio.invalid",
    "GIT_COMMITTER_NAME": "kk-studio test",
    "GIT_COMMITTER_EMAIL": "test@kk-studio.invalid",
}


def entrypoint_environment(overrides=None):
    """Build a clean environment for sourcing the entrypoint with explicit values."""
    environment = dict(os.environ)
    for name in ENTRYPOINT_ENV_NAMES:
        environment.pop(name, None)
    environment.update(overrides or {})
    return environment


def run_git(directory, *arguments, env=None, check=True):
    """Run one git command against a fixture repository with a hermetic configuration."""
    environment = dict(os.environ)
    environment.update(GIT_FIXTURE_ENV)
    environment.update(env or {})
    result = subprocess.run(
        ["git", *arguments],
        cwd=directory,
        text=True,
        capture_output=True,
        check=False,
        env=environment,
    )
    if check and result.returncode != 0:
        raise AssertionError(f"git {' '.join(arguments)} failed: {result.stderr}")
    return result


def commit_file(directory, name, content, message):
    """Commit one file into a fixture repository and return the resulting revision."""
    (Path(directory) / name).write_text(content)
    run_git(directory, "add", name)
    run_git(directory, "commit", "-m", message)
    return run_git(directory, "rev-parse", "HEAD").stdout.strip()


def build_workspace_fixture(root, branch="dev"):
    """Create a bare `origin`, a seed clone that publishes to it, and a workspace clone.

    The workspace is cloned from a `file://` bare repository, so every revision-sync
    behavior can be exercised without network access or real credentials.
    """
    root = Path(root)
    origin = root / "origin.git"
    seed = root / "seed"
    workspace = root / "workspace"
    run_git(root, "init", "--bare", "--initial-branch", branch, str(origin))
    run_git(root, "init", "--initial-branch", branch, str(seed))
    commit_file(seed, "tracked.txt", "one\n", "one")
    run_git(seed, "remote", "add", "origin", f"file://{origin}")
    run_git(seed, "push", "-u", "origin", branch)
    run_git(root, "clone", "--branch", branch, f"file://{origin}", str(workspace))
    return origin, seed, workspace


def publish(seed, branch, name, content, message):
    """Advance the fixture remote by one commit and return the new revision."""
    revision = commit_file(seed, name, content, message)
    run_git(seed, "push", "origin", branch)
    return revision


def source_entrypoint(script, env=None):
    """Source the entrypoint in a fresh Bash and run one ad hoc `script` snippet.

    Sourcing is what turns the entrypoint into a behavioral contract: the same functions the
    container runs can be exercised directly without preparing a workspace or starting servers.
    """
    return subprocess.run(
        ["bash", "-c", 'source "$1"\n' + script, "bash", str(DEV_ENTRYPOINT)],
        cwd=REPOSITORY_ROOT,
        text=True,
        capture_output=True,
        check=False,
        env=entrypoint_environment(env),
    )


class TestNasDevImageContracts(unittest.TestCase):
    """The Dev image must stay tool-complete, non-root, secret-free and reloadable."""

    def test_image_installs_required_development_toolchain(self):
        # Intent: the minimum tool set is what lets an Agent edit, build, test and push; a
        # missing tool would only surface at runtime inside the NAS container.
        dockerfile = DEV_DOCKERFILE.read_text()
        install_block = re.search(
            r"apt-get install -y --no-install-recommends \\\n(?P<packages>[^\n]*)",
            dockerfile,
        )
        self.assertIsNotNone(install_block, "runtime stage must install its toolchain")
        packages = {
            package.strip().rstrip(";")
            for package in install_block.group("packages").split()
        }
        self.assertTrue(
            REQUIRED_APT_PACKAGES.issubset(packages),
            f"missing runtime packages: {sorted(REQUIRED_APT_PACKAGES - packages)}",
        )

        for pinned in (
            "maven:3.9.11-eclipse-temurin-21 AS builder",
            "node:24.14.0-bookworm-slim AS node-runtime",
            "npm install --global npm@11.9.0",
            "eclipse-temurin:21.0.8_9-jdk-jammy",
            "COPY --from=builder /usr/share/maven /opt/maven",
            "COPY --from=node-runtime /usr/local/ /usr/local/",
        ):
            self.assertIn(pinned, dockerfile, pinned)
        # javap must exist so the Daemon's Java toolchain probing works in this image.
        self.assertIn("command -v javap", dockerfile)

    def test_image_installs_pinned_github_cli_with_verified_checksum(self):
        # Intent: the Agent toolchain needs `gh`, and the only auditable way to add it is
        # the official linux/amd64 release artifact after its published SHA-256 matches;
        # an unverified download or a distro-provided version would drift silently.
        dockerfile = DEV_DOCKERFILE.read_text()
        self.assertIn(GITHUB_CLI_DEB_URL, dockerfile)
        self.assertIn(GITHUB_CLI_DEB_SHA256, dockerfile)
        self.assertIn("sha256sum -c -", dockerfile)
        self.assertIn("dpkg -i /tmp/gh.deb", dockerfile)
        self.assertIn('test "$(dpkg --print-architecture)" = "amd64"', dockerfile)
        # The toolchain is proven usable at build time instead of at NAS boot.
        self.assertIn("command -v ssh", dockerfile)
        self.assertIn("command -v gh", dockerfile)
        self.assertIn("gh --version", dockerfile)

    def test_image_trusts_the_official_github_host_keys(self):
        # Intent: Git authenticates over SSH now, so an unknown host key would stall or
        # fail every clone/fetch/push; the image must ship GitHub's published keys, and the
        # pinned key material must still hash to the published SHA256 fingerprints.
        entries = {}
        for line in DEV_KNOWN_HOSTS.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            host, key_type, key_blob = line.split()
            self.assertEqual("github.com", host)
            entries[key_type] = key_blob
        self.assertEqual(set(GITHUB_SSH_KEY_FINGERPRINTS), set(entries))
        for key_type, key_blob in entries.items():
            digest = hashlib.sha256(base64.b64decode(key_blob)).digest()
            fingerprint = "SHA256:" + base64.b64encode(digest).decode().rstrip("=")
            self.assertEqual(GITHUB_SSH_KEY_FINGERPRINTS[key_type], fingerprint)
        self.assertIn(
            "COPY --chmod=0644 deploy/dev/ssh_known_hosts /etc/ssh/ssh_known_hosts",
            DEV_DOCKERFILE.read_text(),
        )
        self.assertIn(
            "COPY --chmod=0644 deploy/dev/ssh_config"
            " /etc/ssh/ssh_config.d/kk-studio-github.conf",
            DEV_DOCKERFILE.read_text(),
        )
        ssh_config = DEV_SSH_CONFIG.read_text()
        for directive in (
            "Host github.com",
            "BatchMode yes",
            "IdentitiesOnly yes",
            "StrictHostKeyChecking yes",
        ):
            self.assertIn(directive, ssh_config)

    def test_image_runs_as_fixed_non_root_identity_with_writable_paths(self):
        # Intent: the Dev node must never run as root and must own its home, workspace
        # and cache directories, which are mounted as persistent volumes.
        dockerfile = DEV_DOCKERFILE.read_text()
        self.assertIn("groupadd --gid 10001 kkdaemon", dockerfile)
        self.assertIn("--uid 10001", dockerfile)
        self.assertIn("HOME=/home/kkdaemon", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)
        for writable in (
            "/opt/kk-studio/lib",
            "/opt/kk-studio/source",
            "/workspace",
            "/var/kk-studio/dev",
            "/home/kkdaemon/.m2",
            "/home/kkdaemon/.npm",
            "/home/kkdaemon/.ssh",
            "/home/kkdaemon/.config/gh",
        ):
            self.assertIn(writable, dockerfile, writable)
        # ssh rejects keys stored in a group/world-readable directory, and gh keeps its auth
        # token under the same home, so both directories are pre-created for uid 10001.
        self.assertIn("chmod 0700 /home/kkdaemon/.ssh /home/kkdaemon/.config/gh", dockerfile)
        self.assertIn(
            "chown -R kkdaemon:kkdaemon /opt/kk-studio /workspace /var/kk-studio /home/kkdaemon",
            dockerfile,
        )
        # No container escape hatch: the self-iteration flow must not require Docker.
        self.assertNotIn("docker.sock", dockerfile)
        self.assertNotIn("privileged", dockerfile)

    def test_image_bakes_daemon_and_source_seed_without_secrets(self):
        # Intent: the Daemon runs from the immutable image, while the source snapshot is
        # only a first-start fallback; neither may carry credentials or local state.
        dockerfile = DEV_DOCKERFILE.read_text()
        self.assertIn(
            "COPY --from=builder --chown=kkdaemon:kkdaemon /build/daemon.jar"
            " /opt/kk-studio/daemon.jar",
            dockerfile,
        )
        self.assertIn(
            "COPY --from=builder --chown=kkdaemon:kkdaemon /build/daemon-lib/ /opt/kk-studio/lib/",
            dockerfile,
        )
        self.assertIn("COPY --chown=kkdaemon:kkdaemon . /opt/kk-studio/source", dockerfile)
        self.assertIn("KK_STUDIO_SOURCE_SEED=/opt/kk-studio/source", dockerfile)
        self.assertIn("ENTRYPOINT [\"/usr/local/bin/kk-studio-dev-entrypoint\"]", dockerfile)
        self.assertIsNone(
            re.search(r"(?im)^ARG .*(token|secret|password|key)", dockerfile),
            "no credential may arrive through a build arg",
        )

        dockerignore = DOCKERIGNORE.read_text()
        for excluded in (
            ".git/",
            ".env",
            "*.pem",
            "*.key",
            "reports/",
            "runtime/",
            "**/target/",
            "frontend/node_modules/",
        ):
            self.assertIn(excluded, dockerignore, excluded)

    def test_entrypoint_initializes_persistent_workspace_without_overwriting_it(self):
        # Intent: the runtime source of truth is a persistent Git checkout, and an existing
        # workspace (including uncommitted Agent work) must survive every container rebuild.
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn(
            "REPOSITORY_DIR=${KK_STUDIO_REPOSITORY_DIR:-$WORKSPACE_ROOT/kk-studio}",
            entrypoint,
        )
        self.assertIn(
            'git clone --branch "$GIT_BRANCH" "$GIT_REMOTE_URL" "$REPOSITORY_DIR"',
            entrypoint,
        )
        self.assertIn('is not a kk-studio checkout and is not empty; not overwriting it', entrypoint)
        # A workspace that was already initialized is reused as-is, without checkout/reset.
        self.assertIn("workspace_is_repository()", entrypoint)
        self.assertIn("Reusing the persistent git workspace", entrypoint)
        self.assertIn("Reusing the persistent workspace", entrypoint)
        self.assertIn("KK_STUDIO_GIT_REMOTE_URL is required", entrypoint)
        self.assertIn("assert_expected_git_branch", entrypoint)
        self.assertIn('symbolic-ref --quiet --short HEAD', entrypoint)
        self.assertIn('must stay on branch $GIT_BRANCH', entrypoint)
        # The embedded snapshot may only be used when it is explicitly opted in.
        self.assertIn("ALLOW_SOURCE_SEED=${KK_STUDIO_DEV_ALLOW_SOURCE_SEED:-false}", entrypoint)
        self.assertIn('if [ "$ALLOW_SOURCE_SEED" = "true" ]; then', entrypoint)

    def test_entrypoint_installs_mounted_ssh_credentials_before_workspace_init(self):
        # Intent: the Agent's Git/gh access comes from the runtime-mounted private key, and a
        # missing or keyless mount must stop the container instead of degrading into a
        # checkout that can never push. Only `id_*` is copied, so the host's ssh config,
        # known_hosts and authorized_keys never leak into the container. The image-owned
        # SSH config makes authentication non-interactive and fail-closed.
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn(
            "SSH_CREDENTIALS_DIR=${KK_STUDIO_SSH_CREDENTIALS_DIR:-/run/kk-studio/ssh}",
            entrypoint,
        )
        self.assertIn("SSH_DIR=$HOME/.ssh", entrypoint)
        body = function_body(DEV_ENTRYPOINT, "install_ssh_credentials")
        self.assertIn(
            'if [ ! -d "$SSH_CREDENTIALS_DIR" ] || [ ! -r "$SSH_CREDENTIALS_DIR" ]; then',
            body,
        )
        self.assertIn('for key in "$SSH_CREDENTIALS_DIR"/id_*; do', body)
        self.assertIn('chmod 0700 "$SSH_DIR"', body)
        self.assertIn('rm -f "$SSH_DIR"/id_*', body)
        self.assertLess(
            body.index('rm -f "$SSH_DIR"/id_*'),
            body.index('for key in "$SSH_CREDENTIALS_DIR"/id_*; do'),
            "stale identities must be removed before the mounted key set is installed",
        )
        self.assertIn('install -m 0600 "$key" "$SSH_DIR/$name"', body)
        self.assertIn('install -m 0644 "$key" "$SSH_DIR/$name"', body)
        self.assertIn("must contain at least one private id_* key", body)
        # Neither the source config/known_hosts/authorized_keys nor the key contents may be
        # read back out by this step.
        for excluded in ("config", "known_hosts", "authorized_keys", "cat "):
            self.assertNotIn(excluded, body, excluded)
        for command in ("gh", "git", "ssh"):
            self.assertIn(f"require_cmd {command}", entrypoint)
        main_body = function_body(DEV_ENTRYPOINT, "main")
        self.assertLess(
            main_body.index("install_ssh_credentials"),
            main_body.index("prepare_workspace"),
            "credentials must be installed before the first clone",
        )
        self.assertLess(
            main_body.index("prepare_workspace"),
            main_body.index("run_daemon"),
            "the workspace must exist before the Daemon starts",
        )

    def test_dev_image_has_no_git_token_or_askpass_flow(self):
        # Intent: SSH is the only Git credential path; a surviving token/askpass identifier
        # would mean a second, undocumented way to authenticate that the mount contract no
        # longer covers.
        for surface in (
            DEV_DOCKERFILE,
            DEV_ENTRYPOINT,
            DEV_RELOAD,
            DEV_HEALTHCHECK,
            DEPLOYMENT_DOC,
            DEVELOPMENT_DOC,
        ):
            body = surface.read_text()
            for identifier in (
                "KK_STUDIO_GIT_USERNAME",
                "KK_STUDIO_GIT_TOKEN",
                "GIT_ASKPASS",
                "askpass",
            ):
                self.assertNotIn(identifier, body, f"{surface.name} must not reference {identifier}")
        self.assertFalse(DEV_ASKPASS.exists(), "the Git askpass helper must be deleted")
        # The clean remote URL is what lands in .git/config; no credential is configured.
        self.assertNotIn("git config", DEV_ENTRYPOINT.read_text())

    def test_dev_image_does_not_configure_a_network_proxy(self):
        # Intent: this application connects directly; adding standard proxy variables to
        # its image or lifecycle would also risk routing NAS-internal traffic externally.
        for surface in DEV_SHELL_SCRIPTS + (DEV_DOCKERFILE,):
            body = surface.read_text()
            for identifier in (
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "NO_PROXY",
                "http_proxy",
                "https_proxy",
                "no_proxy",
            ):
                self.assertNotIn(identifier, body, f"{surface.name} must not configure {identifier}")

    def test_docs_describe_the_mount_and_gh_login_contract(self):
        # Intent: the NAS Compose file lives outside this repository, so the committed docs
        # are the only place where the four persistent mounts and the one-time
        # `gh auth login` step can be verified.
        deployment = DEPLOYMENT_DOC.read_text()
        development = DEVELOPMENT_DOC.read_text()
        for mount in (
            "/workspace",
            "/home/kkdaemon/.m2",
            "/home/kkdaemon/.npm",
            "/home/kkdaemon/.config/gh",
        ):
            self.assertIn(mount, deployment, mount)
            self.assertIn(mount, development, mount)
        for doc in (deployment, development):
            self.assertIn("KK_STUDIO_SSH_CREDENTIALS_DIR", doc)
            self.assertIn("/etc/ssh/ssh_known_hosts", doc)
        # SSH only grants Git access; gh keeps its own API scopes from an interactive login.
        self.assertIn("SSH key 只让 Git 能读写仓库", development)
        for login in (
            "docker exec -it vps-kk-studio-dev gh auth login --hostname github.com"
            " --git-protocol ssh --web --skip-ssh-key --scopes repo,workflow,read:org,gist",
            "docker exec vps-kk-studio-dev gh auth status",
        ):
            self.assertIn(login, development, login)

    def test_entrypoint_starts_managed_servers_then_runs_daemon_in_foreground(self):
        # Intent: the container's main process is the Daemon, and Backend/Vite are started
        # through the existing repository lifecycle with the Dev node's fixed contract. The
        # Daemon registers with Main, so no loopback Dev Backend target may be compiled in.
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn('DAEMON_JAR=/opt/kk-studio/daemon.jar', entrypoint)
        self.assertIn(f"DAEMON_GATEWAY_PATH={DAEMON_GATEWAY_PATH}", entrypoint)
        self.assertIn("CONTROL_PLANE_BASE_URL=${KK_STUDIO_CONTROL_PLANE_BASE_URL:-}", entrypoint)
        self.assertIn('--gateway-uri "$DAEMON_GATEWAY_URI"', entrypoint)
        self.assertIn("SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}", entrypoint)
        self.assertIn("SPRING_FLYWAY_ENABLED=${SPRING_FLYWAY_ENABLED:-false}", entrypoint)
        self.assertIn("BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}", entrypoint)
        self.assertIn("BACKEND_PORT=${BACKEND_PORT:-8080}", entrypoint)
        self.assertIn("FRONTEND_HOST=${FRONTEND_HOST:-0.0.0.0}", entrypoint)
        self.assertIn("FRONTEND_PORT=${FRONTEND_PORT:-5173}", entrypoint)
        self.assertIn('"$REPOSITORY_DIR/scripts/dev.sh" start', entrypoint)
        # 凭证只经 owner-only 文件进入 daemon；argv 中不得再出现明文 token。
        self.assertIn('--registration-token-file "$token_file"', entrypoint)
        self.assertNotIn('--registration-token "$registration_token"', entrypoint)
        self.assertIn('chmod 600 "$token_file"', entrypoint)
        self.assertIn('chmod 700 "$token_dir"', entrypoint)
        self.assertIn('--environment-root "$WORKSPACE_ROOT"', entrypoint)
        self.assertIn(
            "DAEMON_DATA_DIR=${KK_STUDIO_DAEMON_DATA_DIR:-$WORKSPACE_ROOT/.kkstudio/daemon}",
            entrypoint,
        )
        self.assertIn('--data-dir "$DAEMON_DATA_DIR"', entrypoint)
        self.assertNotIn("--skill-dir", entrypoint)
        self.assertIn("exec java", entrypoint)
        # The Daemon binary always comes from the image, never from the mutable workspace.
        self.assertNotIn("$REPOSITORY_DIR/harness/daemon", entrypoint)
        # Top-level execution is wrapped in `main` so the file stays sourceable for the
        # behavioral unit tests, while a direct container start still runs the same sequence.
        main_body = function_body(DEV_ENTRYPOINT, "main")
        self.assertLess(
            main_body.index("start_managed_servers"),
            main_body.index("run_daemon"),
            "the Daemon must start after the managed servers it precedes as container PID 1",
        )
        self.assertIn('if [ "${BASH_SOURCE[0]}" = "$0" ]; then', entrypoint)
        self.assertTrue(
            entrypoint.rstrip().endswith("main\nfi"),
            "only a direct execution may start the container sequence",
        )

    def test_reload_command_restarts_only_managed_processes(self):
        # Intent: `kk-studio-dev-reload` is the Agent's stable post-test command; it must
        # rebuild incrementally and never touch the Daemon, the container or Git state.
        reload_script = DEV_RELOAD.read_text()
        self.assertIn("mvn -B -ntp -pl web -am -DskipTests package", reload_script)
        self.assertNotRegex(reload_script, r"\bclean\b")
        self.assertIn("DEV_SKIP_PACKAGE=true", reload_script)
        self.assertIn('"$REPOSITORY_DIR/scripts/dev.sh" restart', reload_script)
        self.assertIn("REPOSITORY_DIR=${KK_STUDIO_REPOSITORY_DIR:-$WORKSPACE_ROOT/kk-studio}", reload_script)
        # Reload only needs the workspace layout, not git metadata, so an explicit
        # source-snapshot workspace stays reloadable too.
        self.assertIn('[ ! -f "$REPOSITORY_DIR/pom.xml" ]', reload_script)
        for forbidden in ("DaemonMain", "docker ", "pkill", "kill -9", "git push", "git reset"):
            self.assertNotIn(forbidden, reload_script, forbidden)
        self.assertIn("kk-studio-dev-reload", DEV_DOCKERFILE.read_text())

    def test_entrypoint_derives_the_daemon_gateway_uri_from_the_main_origin(self):
        # Intent: external Compose only knows Main's internal HTTP origin, so the container
        # must derive the ws(s) gateway URI (including the fixed protocol path) itself; a
        # committed URI would let the env contract drift from the Server endpoint or point
        # the Daemon back at the Dev container.
        for origin, expected in (
            (CONTROL_PLANE_BASE_URL, DAEMON_GATEWAY_URI),
            ("https://vps-kk-studio:8080", DAEMON_GATEWAY_URI.replace("ws://", "wss://")),
            (f"{CONTROL_PLANE_BASE_URL}/", DAEMON_GATEWAY_URI),
            ("https://vps-kk-studio", f"wss://vps-kk-studio{DAEMON_GATEWAY_PATH}"),
            ("http://vps-kk-studio:8080/", DAEMON_GATEWAY_URI),
        ):
            result = source_entrypoint(f"resolve_daemon_gateway_uri {shlex.quote(origin)}")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(expected, result.stdout.strip())
        main_body = function_body(DEV_ENTRYPOINT, "main")
        self.assertLess(
            main_body.index("DAEMON_GATEWAY_URI=$(resolve_daemon_gateway_uri"),
            main_body.index("install_ssh_credentials"),
            "an invalid origin must fail before the workspace is touched or servers start",
        )

    def test_entrypoint_rejects_every_non_origin_control_plane_value(self):
        # Intent: only a bare HTTP(S) origin may reach the Daemon; ws/wss, paths, query,
        # fragment, userinfo, whitespace and empty values would silently produce a wrong or
        # credential-carrying endpoint, so they must fail without echoing the value into
        # container logs.
        for invalid in REJECTED_ORIGINS:
            result = source_entrypoint(f"resolve_daemon_gateway_uri {shlex.quote(invalid)}")
            self.assertNotEqual(0, result.returncode, f"{invalid!r} must be rejected")
            self.assertIn("KK_STUDIO_CONTROL_PLANE_BASE_URL", result.stderr)
            self.assertNotIn(REJECTED_ORIGIN_MARKER, result.stderr + result.stdout)
            self.assertEqual("", result.stdout)

    def test_entrypoint_materializes_the_token_as_an_owner_only_file(self):
        # Intent: the Daemon must read its credential from a 0600 file instead of argv, so
        # `ps` and `/proc/<pid>/environ` can never expose it. `run_daemon` ends in `exec java`,
        # so a probe `java` on PATH receives exactly the argv the real Daemon would receive.
        with tempfile.TemporaryDirectory() as home:
            probe_dir = Path(home)
            argv_file = probe_dir / "argv.txt"
            probe = probe_dir / "java"
            probe.write_text(
                "#!/bin/bash\n"
                'printf \'%s\\n\' "$@" >"$PROBE_ARGV"\n'
                'env >"$PROBE_ENVIRON"\n'
            )
            probe.chmod(0o755)

            result = source_entrypoint(
                "run_daemon\n",
                {
                    "HOME": home,
                    "PATH": f"{probe_dir}:{os.environ.get('PATH', '')}",
                    "PROBE_ARGV": str(argv_file),
                    "PROBE_ENVIRON": str(probe_dir / "environ.txt"),
                    "DAEMON_GATEWAY_URI": DAEMON_GATEWAY_URI,
                    "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": "probe-secret-token",
                },
            )

            self.assertEqual(0, result.returncode, result.stderr)
            argv = argv_file.read_text().splitlines()
            self.assertIn("--registration-token-file", argv)
            self.assertNotIn("--registration-token", argv)
            # 凭证文本绝不能出现在 argv 或子进程环境中。
            self.assertNotIn("probe-secret-token", argv)
            self.assertNotIn("probe-secret-token", (probe_dir / "environ.txt").read_text())
            self.assertNotIn("probe-secret-token", result.stderr + result.stdout)
            token_path = Path(argv[argv.index("--registration-token-file") + 1])
            self.assertTrue(token_path.is_absolute())
            # 文件必须在 exec 时刻仍然存在、内容正确且为 0600。
            self.assertEqual(0o600, token_path.stat().st_mode & 0o777)
            self.assertEqual("probe-secret-token", token_path.read_text().strip())

    def test_dev_harness_dispatcher_is_disabled_by_default_and_fail_closed(self):
        # Intent: Main is the only Harness worker; the image default, the entrypoint default,
        # Spring mapping and both managed-server paths must all agree, otherwise the Dev node
        # would start competing for the same durable Work.
        dockerfile = DEV_DOCKERFILE.read_text()
        self.assertIn("KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false", dockerfile)
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn(
            "HARNESS_RUNTIME_WORKERS_ENABLED=${KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED:-false}",
            entrypoint,
        )
        runtime_contract = function_body(DEV_ENTRYPOINT, "validate_runtime_contract")
        self.assertIn('HARNESS_RUNTIME_WORKERS_ENABLED" != "false"', runtime_contract)
        self.assertIn("Main is the only Harness worker", runtime_contract)
        self.assertIn(
            "workers-enabled: ${KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED:true}",
            WEB_APPLICATION_CONFIG.read_text(),
        )
        for script in (entrypoint, DEV_RELOAD.read_text()):
            self.assertIn(
                'KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED="$HARNESS_RUNTIME_WORKERS_ENABLED"',
                script,
            )
        reload_script = DEV_RELOAD.read_text()
        self.assertIn(
            "HARNESS_RUNTIME_WORKERS_ENABLED=${KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED:-false}",
            reload_script,
        )
        self.assertIn('HARNESS_RUNTIME_WORKERS_ENABLED" != "false"', reload_script)
        defaulted = source_entrypoint('printf "%s\\n" "$HARNESS_RUNTIME_WORKERS_ENABLED"')
        self.assertEqual(0, defaulted.returncode, defaulted.stderr)
        self.assertEqual("false", defaulted.stdout.strip())
        overridden = source_entrypoint(
            "validate_runtime_contract",
            {
                "SPRING_PROFILES_ACTIVE": "prod",
                "SPRING_FLYWAY_ENABLED": "false",
                "KK_STUDIO_DAEMON_REGISTRATION_TOKEN": "test-token",
                "KK_STUDIO_CONTROL_PLANE_BASE_URL": CONTROL_PLANE_BASE_URL,
                "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED": "true",
            },
        )
        self.assertNotEqual(0, overridden.returncode, "an explicit override must fail closed")

    def test_dev_surfaces_drop_the_removed_gateway_uri_variable(self):
        # Intent: the old Daemon gateway URI contract is what allowed the Dev Daemon to
        # target its own Backend; the image, its scripts and the authoritative docs must not
        # keep a compatibility alias that would silently restore that topology.
        for surface in (
            DEV_DOCKERFILE,
            DEV_ENTRYPOINT,
            DEV_RELOAD,
            DEV_HEALTHCHECK,
            DEPLOYMENT_DOC,
            DEVELOPMENT_DOC,
        ):
            self.assertNotIn(
                "KK_STUDIO_DAEMON_GATEWAY_URI",
                surface.read_text(),
                f"{surface.name} must not reference the removed gateway URI variable",
            )

    def test_docs_state_main_owned_execution_and_dev_synchronous_preview(self):
        # Intent: the NAS Compose file and Gateway config live outside this repository, so
        # the committed docs are the only place defining who executes Work and who owns
        # Flyway; stale claims would mislead operators into re-enabling Dev workers or
        # pointing the Dev Daemon back at its own container.
        deployment = DEPLOYMENT_DOC.read_text()
        development = DEVELOPMENT_DOC.read_text()
        for doc in (deployment, development):
            self.assertIn("KK_STUDIO_CONTROL_PLANE_BASE_URL", doc)
            self.assertIn("KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED", doc)
            self.assertIn("`.env`", doc)
            self.assertIn(
                "KK_STUDIO_CONTROL_PLANE_BASE_URL: ${KK_STUDIO_CONTROL_PLANE_BASE_URL}",
                doc,
            )
            self.assertNotIn("ws://127.0.0.1:8080/api/harness/environment-daemon/v1", doc)
        self.assertIn(f"KK_STUDIO_CONTROL_PLANE_BASE_URL={CONTROL_PLANE_BASE_URL}", development)
        self.assertIn(f"ws://vps-kk-studio:8080{DAEMON_GATEWAY_PATH}", development)

    def test_image_defaults_to_prod_profile_with_flyway_disabled(self):
        # Intent: only Main owns Flyway; the Dev node reuses the prod profile but must never
        # apply migrations to the shared database, so the image default has to be explicit.
        dockerfile = DEV_DOCKERFILE.read_text()
        self.assertIn("SPRING_PROFILES_ACTIVE=prod", dockerfile)
        self.assertIn("SPRING_FLYWAY_ENABLED=false", dockerfile)
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn("SPRING_FLYWAY_ENABLED=${SPRING_FLYWAY_ENABLED:-false}", entrypoint)
        self.assertIn('SPRING_FLYWAY_ENABLED="$SPRING_FLYWAY_ENABLED" \\', entrypoint)
        runtime_contract = function_body(DEV_ENTRYPOINT, "validate_runtime_contract")
        self.assertIn('SPRING_PROFILES_ACTIVE" != "prod"', runtime_contract)
        self.assertIn('SPRING_FLYWAY_ENABLED" != "false"', runtime_contract)
        self.assertIn("Main is the only Flyway owner", runtime_contract)
        main_body = function_body(DEV_ENTRYPOINT, "main")
        self.assertLess(
            main_body.index("validate_runtime_contract"),
            main_body.index("start_managed_servers"),
            "the runtime contract must be validated before any process starts",
        )
        self.assertLess(
            main_body.index("validate_runtime_contract"),
            main_body.index("install_ssh_credentials"),
            "the runtime contract must be validated before the workspace is touched",
        )

    def test_healthcheck_covers_backend_and_vite(self):
        # Intent: a healthy Dev node serves the synchronous API/query preview and the Vite
        # entry Human/Agent use, so the image healthcheck must probe both.
        healthcheck = DEV_HEALTHCHECK.read_text()
        self.assertIn("http://127.0.0.1:${backend_port}/actuator/health", healthcheck)
        self.assertIn("http://127.0.0.1:${frontend_port}/threads", healthcheck)
        self.assertIn("backend_port=${BACKEND_PORT:-8080}", healthcheck)
        self.assertIn("frontend_port=${FRONTEND_PORT:-5173}", healthcheck)
        self.assertIn("CMD /usr/local/bin/kk-studio-dev-healthcheck", DEV_DOCKERFILE.read_text())
        self.assertIn("EXPOSE 5173 8080", DEV_DOCKERFILE.read_text())

    def test_healthcheck_start_period_covers_the_cold_first_boot(self):
        # Intent: the first boot of the Dev image builds the whole backend and installs
        # frontend dependencies, so the grace period must be sized from that measured path
        # (cold Maven/npm over the network) instead of a steady-state restart.
        dockerfile = DEV_DOCKERFILE.read_text()
        match = re.search(r"--start-period=(\d+)s", dockerfile)
        self.assertIsNotNone(match, "the Dev image healthcheck must declare a start period")
        start_period = int(match.group(1))
        self.assertGreaterEqual(
            start_period,
            1800,
            "a cold first boot measured ~29 minutes; a smaller grace period reports unhealthy",
        )
        # The measured value is documented where operators look for it.
        self.assertIn(
            f"--start-period={start_period}s",
            (REPOSITORY_ROOT / "docs/operations/deployment.md").read_text(),
        )

    def test_managed_servers_get_a_container_readiness_budget(self):
        # Intent: the 90 second repository default is not enough for the first in-container
        # boot, and a readiness timeout kills the container, so both the entrypoint and the
        # reload command must pass an explicit container budget that dev.sh honours.
        dev_script = (REPOSITORY_ROOT / "scripts/dev.sh").read_text()
        self.assertIn("READY_TIMEOUT_SECONDS=${DEV_READY_TIMEOUT_SECONDS:-90}", dev_script)
        self.assertIn('for _ in $(seq 1 "$READY_TIMEOUT_SECONDS")', dev_script)
        self.assertIn("DEV_READY_TIMEOUT_SECONDS=90", dev_script)
        for script in (DEV_ENTRYPOINT, DEV_RELOAD):
            body = script.read_text()
            self.assertIn("DEV_READY_TIMEOUT_SECONDS=${DEV_READY_TIMEOUT_SECONDS:-600}", body)
            self.assertIn('DEV_READY_TIMEOUT_SECONDS="$DEV_READY_TIMEOUT_SECONDS" \\', body)

    def test_backend_starts_from_the_repository_root(self):
        # Intent: the backend resolves its own log files against the working directory, and
        # earlier start steps may already have changed it, so the launch must pin $APP_HOME.
        body = function_body(REPOSITORY_ROOT / "scripts/dev.sh", "start_all")
        self.assertLess(
            body.index('ensure_frontend_deps'),
            body.index('cd "$APP_HOME"\n  step "Starting backend on'),
            "the working directory must be pinned after the dependency steps and before the backend launch",
        )

    def test_publish_workflow_selects_the_branch_specific_image(self):
        # Intent: `main` publishes the immutable production image, `dev` the
        # self-iteration image; both keep an immutable commit tag.
        workflow = PUBLISH_WORKFLOW.read_text()
        self.assertRegex(workflow, r"(?m)^on:\n  push:\n    branches:\n      - main\n      - dev\n")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("needs: validate", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        self.assertIn("platforms: linux/amd64", workflow)
        self.assertIn("cache-from: type=gha", workflow)
        self.assertIn("cache-to: type=gha,mode=max", workflow)
        self.assertRegex(
            workflow,
            r"main\)\n(?:\s+echo)[^\n]*\n\s+echo \"image=kk-studio\"[^\n]*\n\s+echo \"tag=main\"",
        )
        self.assertRegex(
            workflow,
            r"dev\)\n(?:\s+echo)[^\n]*\n\s+echo \"image=kk-studio-dev\"[^\n]*\n\s+echo \"tag=dev\"",
        )
        self.assertIn("${{ steps.target.outputs.dockerfile }}", workflow)
        self.assertIn("${{ github.sha }}", workflow)
        # Unknown refs must fail instead of publishing an unintended image.
        self.assertIn("unsupported publish branch", workflow)

    def test_publish_workflow_validates_and_keeps_credentials_in_secrets(self):
        # Intent: images may only be pushed after the repository gates pass, and registry
        # credentials must never become build args, image content or paid E2E runs.
        workflow = PUBLISH_WORKFLOW.read_text()
        for gate in (
            "mvn -B -ntp verify",
            "npm --prefix frontend ci",
            "npm --prefix frontend run lint",
            "npm --prefix frontend run test",
            "npm --prefix frontend run build",
            "node scripts/docs/check.mjs",
            "python3 -m unittest discover -s scripts/e2e/tests",
            "python3 scripts/security/check-sensitive-data.py",
        ):
            self.assertIn(gate, workflow, gate)
        self.assertIn("${{ secrets.DOCKERHUB_USERNAME }}", workflow)
        self.assertIn("${{ secrets.DOCKERHUB_TOKEN }}", workflow)
        self.assertIn("${{ secrets.DOCKERHUB_NAMESPACE }}", workflow)
        self.assertNotIn("build-args", workflow)
        self.assertNotIn("--real", workflow)
        self.assertNotIn("e2e.sh", workflow)

    def test_dev_shell_scripts_are_syntax_clean_and_executable(self):
        # Intent: container startup must fail in CI instead of at NAS boot, so every new
        # shell entry point is parsed and proven executable.
        for script in DEV_SHELL_SCRIPTS:
            self.assertTrue(os.access(script, os.X_OK), f"{script.name} must be executable")
            result = subprocess.run(
                ["bash", "-n", str(script)],
                cwd=REPOSITORY_ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_entrypoint_syncs_the_workspace_revision_between_prepare_and_start(self):
        # Intent: a rebuilt container must never boot a durable workspace whose revision
        # was never verified, and the check must run after the workspace exists and before
        # any server starts. The step order is what makes a stale JAR impossible to serve
        # silently, so it is asserted on the real `main` sequence rather than on prose.
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn(
            "REPOSITORY_DIR=${KK_STUDIO_REPOSITORY_DIR:-$WORKSPACE_ROOT/kk-studio}",
            entrypoint,
        )
        self.assertIn("GIT_BRANCH=${KK_STUDIO_GIT_BRANCH:-dev}", entrypoint)
        main_body = function_body(DEV_ENTRYPOINT, "main")
        self.assertLess(
            main_body.index("prepare_workspace"),
            main_body.index("sync_workspace_revision"),
            "the workspace must exist before its revision is verified",
        )
        self.assertLess(
            main_body.index("sync_workspace_revision"),
            main_body.index("start_managed_servers"),
            "no server may start from an unverified revision",
        )
        self.assertLess(
            main_body.index("sync_workspace_revision"),
            main_body.index("run_daemon"),
            "the Daemon must not start from an unverified revision",
        )

    def test_entrypoint_revision_sync_never_rewrites_history(self):
        # Intent: the sync may only add incoming commits. Any rewrite command would let a
        # container destroy Agent work or published history that nobody reviewed, which is
        # worse than failing to start, so the whole deploy surface is scanned for them.
        for script in (DEV_ENTRYPOINT, DEV_RELOAD):
            body = script.read_text()
            for forbidden in (
                "git reset",
                "git rebase",
                "git stash",
                "git push",
                "git checkout",
                "git clean",
                "git -C \"$REPOSITORY_DIR\" reset",
            ):
                self.assertNotIn(forbidden, body, f"{script.name} must not run {forbidden}")
        sync_body = function_body(DEV_ENTRYPOINT, "sync_workspace_revision")
        self.assertIn('merge --ff-only "origin/$GIT_BRANCH"', sync_body)
        self.assertIn("merge-base --is-ancestor", sync_body)
        # The safety decision belongs to git: no pre-emptive working-tree inspection may
        # reject a workspace that `merge --ff-only` would accept.
        self.assertNotIn("status --porcelain", sync_body)
        self.assertNotIn("--untracked-files", sync_body)

    def test_revision_sync_fast_forwards_a_clean_workspace(self):
        # Intent: the normal self-iteration restart must advance the persistent checkout to
        # the pushed revision instead of silently serving the previous one. The fixture
        # publishes a commit and asserts on the resulting HEAD, so a sync that only prints
        # the step message without merging fails.
        with tempfile.TemporaryDirectory() as temporary:
            _, seed, workspace = build_workspace_fixture(temporary)
            expected = publish(seed, "dev", "tracked.txt", "two\n", "two")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                expected,
                run_git(workspace, "rev-parse", "HEAD").stdout.strip(),
                "the workspace must end up on the fetched revision",
            )
            self.assertEqual(
                "two\n", (workspace / "tracked.txt").read_text(), "the incoming file must be applied"
            )
            self.assertRegex(result.stdout, r"(?i)fast-forward")
            self.assertIn(expected[:7], result.stdout)

    def test_revision_sync_is_a_no_op_when_the_workspace_already_matches(self):
        # Intent: an unchanged node must not touch its checkout at all, otherwise every
        # container restart would be an unreviewed write to the working tree.
        with tempfile.TemporaryDirectory() as temporary:
            _, seed, workspace = build_workspace_fixture(temporary)
            publish(seed, "dev", "tracked.txt", "two\n", "two")
            first = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, first.returncode, first.stderr)
            head = run_git(workspace, "rev-parse", "HEAD").stdout.strip()
            second = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(head, run_git(workspace, "rev-parse", "HEAD").stdout.strip())
            self.assertNotRegex(second.stdout, r"(?i)fast-forward")
            self.assertIn(head[:7], second.stdout)

    def test_revision_sync_fails_closed_when_git_refuses_to_overwrite_local_changes(self):
        # Intent: git itself is the authority on whether a fast-forward is lossless. When
        # the incoming revision would overwrite a local edit, `merge --ff-only` refuses and
        # the container must fail rather than stash, reset or drop that work, so both the
        # exit status and the surviving working tree are asserted.
        with tempfile.TemporaryDirectory() as temporary:
            _, seed, workspace = build_workspace_fixture(temporary)
            local_head = run_git(workspace, "rev-parse", "HEAD").stdout.strip()
            # The remote revision rewrites the very file that is also edited locally.
            expected = publish(seed, "dev", "tracked.txt", "remote revision\n", "remote edit")
            (workspace / "tracked.txt").write_text("local work in progress\n")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertNotEqual(0, result.returncode, "a refused fast-forward must fail closed")
            self.assertEqual(
                local_head,
                run_git(workspace, "rev-parse", "HEAD").stdout.strip(),
                "the revision must not move when the merge was refused",
            )
            self.assertEqual(
                "local work in progress\n",
                (workspace / "tracked.txt").read_text(),
                "the local edit must survive; the entrypoint never stashes or resets",
            )
            self.assertNotIn(expected[:7], run_git(workspace, "rev-parse", "HEAD").stdout)
            self.assertIn("fast-forwarding", result.stderr)
            self.assertIn("local modifications or untracked files", result.stderr)

    def test_revision_sync_allows_untracked_files_to_survive_a_fast_forward(self):
        # Intent: untracked scratch files (reports, local notes) are normal in an Agent
        # workspace and do not conflict with an incoming revision, so they must not block
        # the revision check. Without this the node would need manual cleanup first.
        with tempfile.TemporaryDirectory() as temporary:
            _, seed, workspace = build_workspace_fixture(temporary)
            expected = publish(seed, "dev", "tracked.txt", "two\n", "two")
            (workspace / "scratch.txt").write_text("unrelated\n")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                expected, run_git(workspace, "rev-parse", "HEAD").stdout.strip()
            )
            self.assertEqual("unrelated\n", (workspace / "scratch.txt").read_text())

    def test_revision_sync_keeps_local_edits_the_incoming_revision_does_not_touch(self):
        # Intent: this is the observable contract of handing the safety decision to git.
        # A local edit to a file the remote revision does not modify is not an obstacle, so
        # the workspace must advance to the remote revision and carry that edit along;
        # a pre-emptive `status` check would have refused to start here for no reason.
        with tempfile.TemporaryDirectory() as temporary:
            _, seed, workspace = build_workspace_fixture(temporary)
            local_head = run_git(workspace, "rev-parse", "HEAD").stdout.strip()
            # The remote revision touches a file the local edit does not.
            expected = publish(seed, "dev", "from-remote.txt", "remote\n", "remote work")
            (workspace / "tracked.txt").write_text("local work in progress\n")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotEqual(local_head, expected)
            self.assertEqual(
                expected,
                run_git(workspace, "rev-parse", "HEAD").stdout.strip(),
                "a fast-forward that does not conflict must still be applied",
            )
            self.assertEqual(
                "local work in progress\n",
                (workspace / "tracked.txt").read_text(),
                "the untouched local edit must survive the fast-forward",
            )
            self.assertEqual("remote\n", (workspace / "from-remote.txt").read_text())
            self.assertRegex(result.stdout, r"(?i)fast-forward")

    def test_revision_sync_fails_closed_on_diverged_history(self):
        # Intent: local and remote each hold commits the other lacks, so no fast-forward
        # exists and rewriting either side silently would discard an unreviewed change.
        with tempfile.TemporaryDirectory() as temporary:
            _, seed, workspace = build_workspace_fixture(temporary)
            publish(seed, "dev", "from-remote.txt", "remote\n", "remote work")
            run_git(workspace, "fetch", "origin", "dev")
            local = commit_file(workspace, "local.txt", "local\n", "local work")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertNotEqual(0, result.returncode, "divergence must fail closed")
            self.assertEqual(local, run_git(workspace, "rev-parse", "HEAD").stdout.strip())
            self.assertIn("has diverged from origin/dev", result.stderr)
            self.assertTrue((workspace / "local.txt").exists())

    def test_revision_sync_keeps_serving_unpushed_local_commits(self):
        # Intent: unpushed commits are legitimate durable work on the Dev node (the branch
        # asks Agents to commit before restarting, and pushing is a milestone decision), so
        # the container must keep serving them instead of refusing to start or being pulled
        # back to the remote.
        with tempfile.TemporaryDirectory() as temporary:
            _, _, workspace = build_workspace_fixture(temporary)
            local = commit_file(workspace, "local.txt", "local\n", "local work")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                local,
                run_git(workspace, "rev-parse", "HEAD").stdout.strip(),
                "the local commit must remain HEAD; no reference may move",
            )
            self.assertTrue((workspace / "local.txt").exists())
            self.assertIn("ahead of origin/dev", result.stdout)
            self.assertIn(local[:7], result.stdout)

    def test_revision_sync_fails_closed_when_fetch_is_impossible(self):
        # Intent: the whole point of the gate is that an unverified revision never boots.
        # An unreachable remote (here a missing local path, so no network is needed) must
        # therefore stop the container, and the guidance the entrypoint itself prints must
        # point at credentials/network without echoing the remote URL or any secret (git's
        # own diagnostics are the operator's only source for the underlying reason).
        with tempfile.TemporaryDirectory() as temporary:
            _, _, workspace = build_workspace_fixture(temporary)
            unreachable = Path(temporary) / "absent-origin.git"
            run_git(workspace, "remote", "set-url", "origin", f"file://{unreachable}")
            head = run_git(workspace, "rev-parse", "HEAD").stdout.strip()
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertNotEqual(0, result.returncode, "an unverifiable revision must not start")
            self.assertEqual(head, run_git(workspace, "rev-parse", "HEAD").stdout.strip())
            self.assertIn("git fetch origin dev failed", result.stderr)
            self.assertIn("SSH credentials", result.stderr)
            owned_output = "\n".join(
                line
                for line in result.stderr.splitlines()
                if line.startswith("ERROR:") or line.startswith("       ")
            )
            self.assertIn("never started", owned_output)
            self.assertNotIn(
                str(unreachable),
                owned_output,
                "the entrypoint must not repeat the configured remote URL",
            )

    def test_revision_sync_fails_closed_without_an_origin_remote(self):
        # Intent: without `origin` there is nothing to verify a revision against, so the
        # node would silently run whatever the volume happens to contain.
        with tempfile.TemporaryDirectory() as temporary:
            _, _, workspace = build_workspace_fixture(temporary)
            run_git(workspace, "remote", "remove", "origin")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(workspace), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertNotEqual(0, result.returncode, "a workspace without origin must not start")
            self.assertIn("no origin remote", result.stderr)

    def test_revision_sync_skips_a_source_snapshot_workspace(self):
        # Intent: the mirror-initialized source snapshot has no git metadata and is an
        # explicitly opted-in, intentionally frozen workspace; failing it would remove the
        # documented offline fallback, so the step must succeed and leave files untouched.
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = Path(temporary) / "workspace"
            snapshot.mkdir()
            marker = snapshot / "pom.xml"
            marker.write_text("<project/>\n")
            result = source_entrypoint(
                "sync_workspace_revision",
                {"KK_STUDIO_REPOSITORY_DIR": str(snapshot), "KK_STUDIO_GIT_BRANCH": "dev"},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Skipping revision sync", result.stdout)
            self.assertEqual("<project/>\n", marker.read_text())
            self.assertEqual(["pom.xml"], sorted(entry.name for entry in snapshot.iterdir()))

    def test_artifact_current_binds_a_built_artifact_to_its_revision(self):
        # Intent: `DEV_SKIP_PACKAGE=true` is the only thing standing between a rebuilt
        # container and a JAR from a previous revision, which is exactly the failure this
        # work removes. Each case asserts the boolean decision of the real function.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "kk-studio-web-1.0.0.jar"
            stamp = root / ".kk-studio-revision"
            revision = "9c73718d94be910308e6b075dd70ab7075b2ae46"

            def artifact_current(expected):
                return subprocess.run(
                    [
                        "bash",
                        "-c",
                        (
                            f"source {shlex.quote(str(DEV_SCRIPT))}\n"
                            "artifact_current"
                            f" {shlex.quote(str(artifact))}"
                            f" {shlex.quote(str(stamp))}"
                            f" {shlex.quote(expected)}"
                        ),
                    ],
                    cwd=REPOSITORY_ROOT,
                    text=True,
                    capture_output=True,
                    check=False,
                    env={**os.environ, "DEV_WORK_DIR": str(root / "runtime")},
                )

            self.assertNotEqual(0, artifact_current(revision).returncode, "missing artifact")
            # A source-snapshot workspace has no revision to compare and keeps its artifact.
            artifact.write_text("jar\n")
            self.assertEqual(0, artifact_current("").returncode, "snapshot workspace")
            self.assertNotEqual(0, artifact_current(revision).returncode, "missing stamp")
            stamp.write_text("deadbeef\n")
            self.assertNotEqual(0, artifact_current(revision).returncode, "mismatched stamp")
            for recorded in (f"{revision}\n", f"\n{revision}\n", f"{revision}"):
                stamp.write_text(recorded)
                result = artifact_current(revision)
                self.assertEqual(
                    0,
                    result.returncode,
                    f"stamp {recorded!r} must match release {revision!r}: {result.stderr}",
                )

    def test_dev_script_rebuilds_the_backend_when_the_stamp_is_stale(self):
        # Intent: the stamp written at the previous build is what decides whether Maven may
        # be skipped; the shell wiring must call the same predicate for the JAR and for the
        # frontend lock, otherwise the helper could be correct while the lifecycle still
        # serves stale artifacts.
        package_body = function_body(DEV_SCRIPT, "package_backend")
        self.assertIn(
            'artifact_current "$BACKEND_JAR" "$BACKEND_JAR_REVISION_STAMP" "$(current_revision)"',
            package_body,
        )
        self.assertIn("mvn -pl web -am -DskipTests clean package", package_body)
        self.assertIn(
            'printf \'%s\\n\' "$(current_revision)" > "$BACKEND_JAR_REVISION_STAMP"',
            package_body,
        )
        # `mvn clean` removes the JAR and the stamp together, so the stamp can never claim
        # that a deleted artifact is current.
        self.assertIn(
            'BACKEND_JAR_REVISION_STAMP="$APP_HOME/web/target/.kk-studio-revision"',
            DEV_SCRIPT.read_text(),
        )
        revision_fn = function_body(DEV_SCRIPT, "current_revision")
        self.assertIn('git -C "$APP_HOME" rev-parse HEAD', revision_fn)
        self.assertIn("|| true", revision_fn)

    def test_ensure_frontend_deps_reinstalls_only_when_the_lock_changed(self):
        # Intent: `npm install` is only correct for a missing tree; an upgraded
        # `package-lock.json` needs `npm ci`, otherwise a restarted container keeps
        # dependency versions that the current lock file no longer allows. Each case runs
        # the real function against a recorder `npm`, so the assertions are about the
        # commands the lifecycle actually issues rather than about the script text.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app_home = root / "app"
            (app_home / "scripts").mkdir(parents=True)
            shutil.copy(DEV_SCRIPT, app_home / "scripts" / "dev.sh")
            frontend = app_home / "frontend"
            frontend.mkdir()
            lock = frontend / "package-lock.json"
            lock.write_text('{"lockfileVersion": 3}\n')
            node_modules = frontend / "node_modules"
            stamp = node_modules / ".kk-studio-package-lock.sha"
            bin_dir = root / "bin"
            bin_dir.mkdir()
            npm_log = root / "npm.log"
            recorder = bin_dir / "npm"
            recorder.write_text(
                "#!/usr/bin/env bash\n"
                'printf \'%s\\n\' "$*" >> "$NPM_LOG"\n'
                # A real install also (re)creates the tree the stamp lives in.
                "mkdir -p node_modules\n"
            )
            recorder.chmod(0o755)

            def run_ensure(overrides=None):
                environment = dict(os.environ)
                environment.pop("DEV_SKIP_NPM_INSTALL", None)
                environment.update(
                    {
                        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                        "NPM_LOG": str(npm_log),
                        "DEV_WORK_DIR": str(root / "runtime"),
                    }
                )
                environment.update(overrides or {})
                return subprocess.run(
                    ["bash", "-c", "source scripts/dev.sh\nensure_frontend_deps"],
                    cwd=app_home,
                    text=True,
                    capture_output=True,
                    check=False,
                    env=environment,
                )

            def calls():
                recorded = npm_log.read_text().splitlines() if npm_log.exists() else []
                if npm_log.exists():
                    npm_log.unlink()
                return recorded

            # A missing tree is a plain install, and the fresh tree is stamped.
            result = run_ensure()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["install"], calls())
            self.assertTrue(node_modules.is_dir())
            first_digest = stamp.read_text().strip()
            self.assertRegex(first_digest, r"^[0-9a-f]{40}$")
            self.assertEqual(
                run_git(frontend, "hash-object", "package-lock.json").stdout.strip(),
                first_digest,
                "the stamp must be the lock content hash that a later start recomputes",
            )

            # An unchanged lock reuses the installed tree without invoking npm at all.
            result = run_ensure()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], calls())
            self.assertEqual(first_digest, stamp.read_text().strip())

            # A changed lock must force `npm ci` and refresh the stamp.
            lock.write_text('{"lockfileVersion": 3, "changed": 1}\n')
            result = run_ensure()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["ci"], calls())
            self.assertNotEqual(first_digest, stamp.read_text().strip())

            # The explicit skip switch stays a total opt-out: with a changed lock and even
            # with no tree at all, the lifecycle must not invoke npm.
            lock.write_text('{"lockfileVersion": 3, "changed": 2}\n')
            result = run_ensure({"DEV_SKIP_NPM_INSTALL": "true"})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], calls())
            shutil.rmtree(node_modules)
            result = run_ensure({"DEV_SKIP_NPM_INSTALL": "true"})
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], calls())
            self.assertFalse(
                node_modules.exists(),
                "an explicit skip must not install anything, not even for a missing tree",
            )
            # `npm` is what recreates the skipped tree, so the stamp it owned is gone too;
            # the failed-install case below starts from an explicit, known stamp value.
            node_modules.mkdir()
            previous_digest = "0" * 40
            stamp.write_text(f"{previous_digest}\n")

            # A failed install must not leave a fresh-looking stamp, otherwise the next
            # start would reuse a tree that npm never finished rebuilding.
            failing_bin = root / "failing-bin"
            failing_bin.mkdir()
            failing_npm = failing_bin / "npm"
            failing_npm.write_text("#!/usr/bin/env bash\nexit 1\n")
            failing_npm.chmod(0o755)
            lock.write_text('{"lockfileVersion": 3, "changed": 3}\n')
            result = run_ensure(
                {"PATH": f"{failing_bin}{os.pathsep}{bin_dir}{os.pathsep}{os.environ['PATH']}"}
            )
            self.assertNotEqual(0, result.returncode, "a failed npm must fail the start")
            self.assertEqual(
                previous_digest,
                stamp.read_text().strip(),
                "the stamp must only be refreshed after npm succeeded",
            )

            # Without a lock file there is nothing to compare, so the tree is reused.
            lock.unlink()
            shutil.rmtree(node_modules)
            node_modules.mkdir()
            result = run_ensure()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([], calls())
            self.assertFalse(stamp.exists(), "no lock file means no stamp to maintain")
            self.assertNotEqual(
                0,
                subprocess.run(
                    ["bash", "-c", "source scripts/dev.sh\nfrontend_package_lock_digest"],
                    cwd=app_home,
                    text=True,
                    capture_output=True,
                    check=False,
                    env={**os.environ, "DEV_WORK_DIR": str(root / "runtime")},
                ).returncode,
                "a missing lock file must be reported by status, not by a fabricated digest",
            )

    def test_reload_records_the_revision_for_the_next_container_start(self):
        # Intent: reload already produced a JAR for the current revision, so recording it
        # avoids a redundant full rebuild on the next container start while keeping the
        # stamp honest. The reload command must not gain a `clean` or any Git rewrite.
        reload_script = DEV_RELOAD.read_text()
        self.assertIn("web/target/.kk-studio-revision", reload_script)
        self.assertIn("rev-parse HEAD", reload_script)
        self.assertIn('printf \'%s\\n\' "$revision"', reload_script)
        self.assertNotRegex(reload_script, r"\bclean\b")
        for forbidden in ("git reset", "git checkout", "git stash", "git push"):
            self.assertNotIn(forbidden, reload_script, forbidden)

    def test_ssh_config_bounds_the_connection_setup(self):
        # Intent: a black-holed network would otherwise leave `git fetch` in the entrypoint
        # hanging for the kernel TCP timeout, delaying the mandatory fail-closed decision
        # by minutes; the existing non-interactive guarantees must stay.
        ssh_config = DEV_SSH_CONFIG.read_text()
        self.assertIn("ConnectTimeout 10", ssh_config)
        for directive in ("BatchMode yes", "IdentitiesOnly yes", "StrictHostKeyChecking yes"):
            self.assertIn(directive, ssh_config)


if __name__ == "__main__":
    unittest.main()
