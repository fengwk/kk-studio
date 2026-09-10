"""Permanent guards for the NAS dev node image, its reload command and the publish workflow."""

import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
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
DOCKERIGNORE = REPOSITORY_ROOT / ".dockerignore"
PUBLISH_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "docker-publish.yml"
DEPLOYMENT_DOC = REPOSITORY_ROOT / "docs" / "operations" / "deployment.md"
DEVELOPMENT_DOC = REPOSITORY_ROOT / "docs" / "operations" / "development-and-testing.md"
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
        self.assertEqual(
            [
                "validate_runtime_contract",
                "install_ssh_credentials",
                "prepare_workspace",
                "start_managed_servers",
                "run_daemon",
            ],
            entrypoint.rstrip().splitlines()[-5:],
            "credentials must be installed before the first clone and the Daemon start",
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
        # through the existing repository lifecycle with the Dev node's fixed contract.
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn('DAEMON_JAR=/opt/kk-studio/daemon.jar', entrypoint)
        self.assertIn(
            "ws://127.0.0.1:8080/api/harness/environment-daemon/v1", entrypoint
        )
        self.assertIn("SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-prod}", entrypoint)
        self.assertIn("SPRING_FLYWAY_ENABLED=${SPRING_FLYWAY_ENABLED:-false}", entrypoint)
        self.assertIn("BACKEND_HOST=${BACKEND_HOST:-127.0.0.1}", entrypoint)
        self.assertIn("BACKEND_PORT=${BACKEND_PORT:-8080}", entrypoint)
        self.assertIn("FRONTEND_HOST=${FRONTEND_HOST:-0.0.0.0}", entrypoint)
        self.assertIn("FRONTEND_PORT=${FRONTEND_PORT:-5173}", entrypoint)
        self.assertIn('"$REPOSITORY_DIR/scripts/dev.sh" start', entrypoint)
        self.assertIn('--registration-token "$registration_token"', entrypoint)
        self.assertIn('--environment-root "$WORKSPACE_ROOT"', entrypoint)
        self.assertIn("exec java", entrypoint)
        # The Daemon binary always comes from the image, never from the mutable workspace.
        self.assertNotIn("$REPOSITORY_DIR/harness/daemon", entrypoint)
        self.assertTrue(
            entrypoint.rstrip().endswith(
                "install_ssh_credentials\nprepare_workspace\nstart_managed_servers\nrun_daemon"
            ),
            "the entrypoint must inject SSH keys, prepare the workspace, then run the Daemon",
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
        self.assertLess(
            entrypoint.index("validate_runtime_contract\ninstall_ssh_credentials\nprepare_workspace"),
            entrypoint.index("start_managed_servers\nrun_daemon"),
        )

    def test_healthcheck_covers_backend_and_vite(self):
        # Intent: a healthy Dev node serves both the API the Daemon registers against and
        # the Vite entry Human/Agent use, so the image healthcheck must probe both.
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


if __name__ == "__main__":
    unittest.main()
