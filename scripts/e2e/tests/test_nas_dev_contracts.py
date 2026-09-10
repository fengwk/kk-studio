"""Permanent guards for the NAS dev node image, its reload command and the publish workflow."""

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
DEV_ASKPASS = DEV_ROOT / "git-askpass.sh"
DOCKERIGNORE = REPOSITORY_ROOT / ".dockerignore"
PUBLISH_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "docker-publish.yml"
DEV_SHELL_SCRIPTS = (DEV_ENTRYPOINT, DEV_RELOAD, DEV_HEALTHCHECK, DEV_ASKPASS)

REQUIRED_APT_PACKAGES = {
    "bash",
    "ca-certificates",
    "curl",
    "ffmpeg",
    "git",
    "jq",
    "lsof",
    "python3",
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
        ):
            self.assertIn(writable, dockerfile, writable)
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

    def test_entrypoint_scopes_git_secret_to_clone_and_daemon_only(self):
        # Intent: backend/Vite must never inherit the Git token, while the Daemon keeps it
        # so the Agent's Bash capability can push without writing it into .git/config.
        entrypoint = DEV_ENTRYPOINT.read_text()
        self.assertIn("unset KK_STUDIO_GIT_USERNAME KK_STUDIO_GIT_TOKEN", entrypoint)
        self.assertRegex(
            entrypoint,
            r"\(\n"
            r"\s+#[^\n]*\n"
            r"\s+export KK_STUDIO_GIT_USERNAME=\"\$git_username\"\n"
            r"\s+export KK_STUDIO_GIT_TOKEN=\"\$git_token\"\n"
            r"\s+git clone",
        )
        self.assertNotIn("KK_STUDIO_GIT_TOKEN", function_body(DEV_ENTRYPOINT, "start_managed_servers"))
        socket_export = function_body(DEV_ENTRYPOINT, "run_daemon")
        self.assertIn('export KK_STUDIO_GIT_USERNAME="$git_username"', socket_export)
        self.assertIn('export KK_STUDIO_GIT_TOKEN="$git_token"', socket_export)
        # The clean remote URL is what lands in .git/config; credentials stay in the helper.
        self.assertNotIn("git config", entrypoint)
        askpass = DEV_ASKPASS.read_text()
        self.assertIn("KK_STUDIO_GIT_TOKEN:-", askpass)
        self.assertNotIn("echo", askpass)

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
            entrypoint.rstrip().endswith("prepare_workspace\nstart_managed_servers\nrun_daemon"),
            "the entrypoint must prepare the workspace, start managed servers, then run the Daemon",
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
            entrypoint.index("validate_runtime_contract\nprepare_workspace"),
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
