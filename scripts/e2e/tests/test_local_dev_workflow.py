"""Permanent contracts for the packaged app image, the laptop preview and its config loader."""

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
DEV_SCRIPT = REPOSITORY_ROOT / "scripts" / "dev.sh"
LOCAL_DEV_SCRIPT = REPOSITORY_ROOT / "scripts" / "local-dev.sh"
LOCAL_DEV_TEMPLATE = REPOSITORY_ROOT / "scripts" / "local-dev.config.example"
PUBLISH_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "docker-publish.yml"

# 外部数据面凭据：wrapper 未设置标记时不做完整性校验，标记生效后缺一不可。
REQUIRED_KEYS = (
    "KK_STUDIO_DB_URL",
    "KK_STUDIO_DB_USER",
    "KK_STUDIO_DB_PASSWORD",
    "KK_STUDIO_STORAGE_S3_ENDPOINT",
    "KK_STUDIO_STORAGE_S3_PUBLIC_ENDPOINT",
    "KK_STUDIO_STORAGE_S3_REGION",
    "KK_STUDIO_STORAGE_S3_BUCKET",
    "KK_STUDIO_STORAGE_S3_ACCESS_KEY",
    "KK_STUDIO_STORAGE_S3_SECRET_KEY",
)
OPTIONAL_KEYS = (
    "KK_STUDIO_PLUGINS_CREDENTIAL_KEY_FILE",
    "KK_STUDIO_CANVAS_H3_COMFY_BEARER_TOKEN",
)
WRAPPER_MARKER = "KK_STUDIO_LOCAL_DEV_EXTERNAL_SERVICES"

# 配置值统一带这个前缀，便于断言任何失败路径都没有回显过真实内容。
VALUE_PREFIX = "fixture-value-"


def placeholder_config(omit=(), empty=(), optional=False):
    """Render a syntactically valid owner-only config fixture with marker values."""
    lines = ["# kk-studio local-dev fixture", ""]
    keys = REQUIRED_KEYS + (OPTIONAL_KEYS if optional else ())
    for key in keys:
        if key in omit:
            continue
        value = "" if key in empty else f"{VALUE_PREFIX}{key.lower()}"
        lines.append(f"{key}={value}")
    return "\n".join(lines) + "\n"


def write_config(directory, content, name="local-dev.env", mode=0o600):
    """Write a config fixture with an explicit mode; the loader reads permissions."""
    path = Path(directory) / name
    path.write_text(content)
    path.chmod(mode)
    return path


def clean_environment():
    """Return an environment without any inherited local-dev or data-plane input."""
    environment = dict(os.environ)
    for name in (
        "DEV_ENV_FILE",
        WRAPPER_MARKER,
        "SPRING_PROFILES_ACTIVE",
        "SPRING_FLYWAY_ENABLED",
        "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED",
        "BACKEND_PORT",
        "FRONTEND_PORT",
        "BACKEND_HOST",
        "FRONTEND_HOST",
    ):
        environment.pop(name, None)
    for key in REQUIRED_KEYS + OPTIONAL_KEYS:
        environment.pop(key, None)
    return environment


def source_dev_script(snippet, overrides=None):
    """Source the real dev.sh and run one snippet against its parsed configuration."""
    environment = clean_environment()
    environment.update(overrides or {})
    return subprocess.run(
        ["bash", "-c", f"source {shlex.quote(str(DEV_SCRIPT))}\n{snippet}"],
        cwd=REPOSITORY_ROOT,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
    )


def run_wrapper(arguments, overrides=None):
    """Run the laptop entry point with only the inputs a caller is allowed to provide."""
    environment = {
        "PATH": os.environ["PATH"],
        "HOME": os.environ.get("HOME", "/"),
    }
    for name in (
        "DEV_ENV_FILE",
        WRAPPER_MARKER,
        "SPRING_PROFILES_ACTIVE",
        "SPRING_FLYWAY_ENABLED",
        "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED",
        "BACKEND_PORT",
        "FRONTEND_PORT",
    ):
        environment.pop(name, None)
    environment.update(overrides or {})
    return subprocess.run(
        ["bash", str(LOCAL_DEV_SCRIPT), *arguments],
        cwd=REPOSITORY_ROOT,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
    )


def git_grep(needles, paths=()):
    """Return (path, line_number, text) matches for tracked files, read-only."""
    command = ["git", "grep", "-n", "-I", "-F"]
    for needle in needles:
        command.extend(["-e", needle])
    command.extend(["--", *paths] if paths else ["--", "."])
    result = subprocess.run(
        command,
        cwd=REPOSITORY_ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode not in (0, 1):
        raise AssertionError(result.stderr)
    matches = []
    for line in result.stdout.splitlines():
        path, line_number, text = line.split(":", 2)
        matches.append((path, int(line_number), text))
    return matches


class TestAppImagePublishWorkflow(unittest.TestCase):
    """Both branches publish the same packaged app image; only the mutable tag differs."""

    def test_both_branches_publish_one_dockerfile_and_one_image_repository(self):
        """dev and main must build deploy/local/Dockerfile and push repository `kk-studio`."""
        workflow = PUBLISH_WORKFLOW.read_text()

        self.assertIn("file: deploy/local/Dockerfile", workflow)
        self.assertNotIn("deploy/dev", workflow)
        self.assertNotIn("kk-studio-dev", workflow)
        self.assertIn(
            "${{ secrets.DOCKERHUB_NAMESPACE }}/kk-studio:${{ steps.target.outputs.tag }}",
            workflow,
        )
        self.assertIn(
            "${{ secrets.DOCKERHUB_NAMESPACE }}/kk-studio:${{ github.sha }}",
            workflow,
        )

    def test_immutable_tag_is_selected_fail_closed_from_the_pushed_branch(self):
        """Only main/dev yield a mutable tag; every other ref must fail instead of guessing."""
        workflow = PUBLISH_WORKFLOW.read_text()

        for branch in ("main", "dev"):
            self.assertRegex(
                workflow,
                rf"(?m)^\s+{branch}\)\n\s+echo \"tag={branch}\"",
            )
        self.assertIn('echo "unsupported publish branch: ${GITHUB_REF_NAME}" >&2', workflow)

    def test_repository_validation_stays_main_only_and_credentials_stay_in_secrets(self):
        """The expensive gates stay on main; registry credentials never become build inputs."""
        workflow = PUBLISH_WORKFLOW.read_text()

        self.assertIn("if: github.ref_name == 'main'", workflow)
        self.assertIn("needs.validate.result == 'skipped'", workflow)
        self.assertIn("!cancelled()", workflow)
        self.assertIn("password: ${{ secrets.DOCKERHUB_TOKEN }}", workflow)
        self.assertNotIn("build-args", workflow)


class TestLocalDevEntryPoint(unittest.TestCase):
    """scripts/local-dev.sh is the one-command laptop preview over the packaged Backend."""

    def test_wrapper_forces_prod_profile_without_flyway_or_harness_workers(self):
        """The laptop must never own migrations or distributed Harness work."""
        wrapper = LOCAL_DEV_SCRIPT.read_text()

        for forced in (
            "export SPRING_PROFILES_ACTIVE=prod",
            "export SPRING_FLYWAY_ENABLED=false",
            "export KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false",
            f"export {WRAPPER_MARKER}=true",
        ):
            self.assertIn(forced, wrapper)
        self.assertIn('exec "$SCRIPT_HOME/dev.sh" "$@"', wrapper)

    def test_wrapper_uses_the_owner_only_home_config_unless_overridden(self):
        """The default config path is under HOME; DEV_ENV_FILE is the only override."""
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary) / "home"
            config_dir = home / ".config" / "kk-studio"
            config_dir.mkdir(parents=True)
            write_config(config_dir, placeholder_config())

            result = run_wrapper(["status"], {"HOME": str(home)})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("backend: stopped", result.stdout)
            # Wrapper 不设置监听地址：Backend/Vite 仍由 scripts/dev.sh 默认绑定 loopback。
            self.assertIn("url=http://127.0.0.1:18080", result.stdout)
            self.assertIn("url=http://127.0.0.1:5173", result.stdout)

            (config_dir / "local-dev.env").unlink()
            override_dir = Path(temporary) / "elsewhere"
            override_dir.mkdir()
            override = write_config(override_dir, placeholder_config())
            result = run_wrapper(["status"], {"HOME": str(home), "DEV_ENV_FILE": str(override)})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_wrapper_overrides_conflicting_caller_switches_but_keeps_listen_overrides(self):
        """Caller-provided profile/worker values are replaced; listen ports are respected."""
        with tempfile.TemporaryDirectory() as temporary:
            config = write_config(temporary, placeholder_config())
            result = run_wrapper(
                ["status"],
                {
                    "DEV_ENV_FILE": str(config),
                    "SPRING_PROFILES_ACTIVE": "e2e",
                    "SPRING_FLYWAY_ENABLED": "true",
                    "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED": "true",
                    "BACKEND_PORT": "19080",
                    "FRONTEND_PORT": "5199",
                },
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("url=http://127.0.0.1:19080", result.stdout)
            self.assertIn("url=http://127.0.0.1:5199", result.stdout)

    def test_wrapper_fails_closed_without_a_config_file(self):
        """A missing config must stop the preview instead of starting a prod app without data."""
        with tempfile.TemporaryDirectory() as temporary:
            result = run_wrapper(["status"], {"HOME": temporary})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("DEV_ENV_FILE", result.stderr)

    def test_wrapper_refuses_relaxed_config_permissions_without_echoing_values(self):
        """Group/other-readable config must fail closed and never print its content."""
        with tempfile.TemporaryDirectory() as temporary:
            config = write_config(temporary, placeholder_config(), mode=0o644)
            result = run_wrapper(["status"], {"DEV_ENV_FILE": str(config)})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("group or other permissions", result.stderr)
            self.assertNotIn(VALUE_PREFIX, result.stdout + result.stderr)

    def test_wrapper_help_and_empty_invocation_describe_the_forwarded_commands(self):
        """`--help` documents the forwarded lifecycle and the config override; no args fails."""
        help_result = run_wrapper(["--help"])
        self.assertEqual(0, help_result.returncode, help_result.stdout + help_result.stderr)
        self.assertIn("start|stop|restart|status|logs|tail", help_result.stdout)
        self.assertIn("DEV_ENV_FILE", help_result.stdout)

        empty_result = run_wrapper([])
        self.assertNotEqual(0, empty_result.returncode)
        self.assertIn("Usage:", empty_result.stdout + empty_result.stderr)


class TestLocalDevConfigLoader(unittest.TestCase):
    """scripts/dev.sh loads the external data-plane file only when asked, and fail closed."""

    def test_generic_use_without_a_config_file_is_unchanged(self):
        """Without DEV_ENV_FILE the script keeps its own e2e defaults and reads no file."""
        result = source_dev_script('printf "%s\\n" "$SPRING_PROFILE" "$BACKEND_PORT" "$FRONTEND_PORT"')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["e2e", "18080", "5173"], result.stdout.split())
        self.assertNotIn(VALUE_PREFIX, result.stdout)

    def test_allowed_values_reach_only_an_explicit_backend_child(self):
        """Parsed values stay shell-private until the Backend handoff and are then unexported."""
        password_key = "KK_STUDIO_DB_PASSWORD"
        with tempfile.TemporaryDirectory() as temporary:
            config = write_config(
                temporary,
                f"# comment line\n\n{password_key}=pa=ss=word\n",
            )
            result = source_dev_script(
                (
                    f'if printenv {password_key} >/dev/null; then echo PRE_EXPORTED; fi\n'
                    "set_loaded_dev_env_exported true\n"
                    f'bash -c \'printf "CHILD:%s\\\\n" "${password_key}"\'\n'
                    "set_loaded_dev_env_exported false\n"
                    f'if printenv {password_key} >/dev/null; then echo POST_EXPORTED; fi\n'
                    f'printf "RESULT:%s\\n" "${password_key}"'
                ),
                {"DEV_ENV_FILE": str(config)},
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            # 只按第一个 `=` 拆分，值按字面量读取，不做任何 shell 求值。
            self.assertIn("CHILD:pa=ss=word", result.stdout)
            self.assertIn("RESULT:pa=ss=word", result.stdout)
            self.assertIn("values not printed", result.stdout)
            self.assertNotIn("PRE_EXPORTED", result.stdout)
            self.assertNotIn("POST_EXPORTED", result.stdout)

    def test_start_exports_data_plane_only_around_backend_fork(self):
        """Maven/npm/Vite must not inherit the owner-only data-plane credentials."""
        start = function_body(DEV_SCRIPT, "start_all")
        backend_handoff = function_body(DEV_SCRIPT, "run_backend_detached")

        self.assertIn('run_backend_detached "$BACKEND_LOG"', start)
        self.assertIn('run_detached "$FRONTEND_LOG"', start)
        self.assertNotIn('run_backend_detached "$FRONTEND_LOG"', start)
        self.assertIn("set_loaded_dev_env_exported true", backend_handoff)
        self.assertIn('run_detached "$log_file" "$@"', backend_handoff)
        self.assertIn("set_loaded_dev_env_exported false", backend_handoff)

    def test_crlf_line_endings_do_not_leak_into_values(self):
        """A config written with CRLF must still yield the exact value."""
        with tempfile.TemporaryDirectory() as temporary:
            config = write_config(
                temporary,
                "KK_STUDIO_DB_USER=crlf-user\r\nKK_STUDIO_DB_URL=jdbc:postgresql://host/db\r\n",
            )
            result = source_dev_script(
                'printf "RESULT:[%s]" "$KK_STUDIO_DB_USER"',
                {"DEV_ENV_FILE": str(config)},
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("RESULT:[crlf-user]", result.stdout)

    def test_file_requirements_are_enforced_before_parsing(self):
        """Absolute path, regular non-symlink file, owner-only mode and owner are all checked."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid = write_config(root, placeholder_config())

            relative = subprocess.run(
                ["bash", "-c", f"source {shlex.quote(str(DEV_SCRIPT))}\n:"],
                cwd=root,
                env={**clean_environment(), "DEV_ENV_FILE": "local-dev.env"},
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, relative.returncode)
            self.assertIn("absolute path", relative.stderr)

            missing = source_dev_script(":", {"DEV_ENV_FILE": str(root / "missing.env")})
            self.assertNotEqual(0, missing.returncode)
            self.assertIn("existing regular file", missing.stderr)

            directory = source_dev_script(":", {"DEV_ENV_FILE": str(root)})
            self.assertNotEqual(0, directory.returncode)
            self.assertIn("existing regular file", directory.stderr)

            symlink = root / "linked.env"
            symlink.symlink_to(valid)
            result = source_dev_script(":", {"DEV_ENV_FILE": str(symlink)})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("symbolic link", result.stderr)

            for mode in (0o640, 0o604, 0o666):
                valid.chmod(mode)
                result = source_dev_script(":", {"DEV_ENV_FILE": str(valid)})
                self.assertNotEqual(0, result.returncode, f"mode {mode:o} must be rejected")
                self.assertIn("group or other permissions", result.stderr)
            valid.chmod(0o600)

    def test_a_file_owned_by_another_user_is_rejected(self):
        """The owner check must reject a config owned by another account (stat is simulated)."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = write_config(root, placeholder_config())
            bin_dir = root / "bin"
            bin_dir.mkdir()
            # 只替换属主查询结果，权限查询仍走真实 stat，因此失败必须来自属主校验。
            shim = bin_dir / "stat"
            shim.write_text(
                "#!/usr/bin/env bash\n"
                'if [ "$1" = "-c" ] && [ "$2" = "%u" ]; then\n'
                '  printf "%s\\n" "$(( $(id -u) + 1 ))"\n'
                "  exit 0\n"
                "fi\n"
                'exec /usr/bin/stat "$@"\n'
            )
            shim.chmod(0o755)

            result = source_dev_script(
                ":",
                {
                    "DEV_ENV_FILE": str(config),
                    "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
                },
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("owned by the current user", result.stderr)
            self.assertNotIn(VALUE_PREFIX, result.stdout + result.stderr)

    def test_malformed_and_unknown_keys_fail_without_echoing_the_line(self):
        """A malformed line or a non-whitelisted key is a configuration error, values stay hidden."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            malformed_marker = "malformed-marker-value"
            malformed = write_config(root, f"# comment\n{malformed_marker}\n", name="malformed.env")
            result = source_dev_script(":", {"DEV_ENV_FILE": str(malformed)})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("malformed DEV_ENV_FILE line 2", result.stderr)
            self.assertNotIn(malformed_marker, result.stdout + result.stderr)

            unknown_value = "unknown-marker-value"
            unknown = write_config(
                root,
                f"KK_STUDIO_NOT_ALLOWED={unknown_value}\n",
                name="unknown.env",
            )
            result = source_dev_script(":", {"DEV_ENV_FILE": str(unknown)})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("unknown key on DEV_ENV_FILE line 1: KK_STUDIO_NOT_ALLOWED", result.stderr)
            self.assertNotIn(unknown_value, result.stdout + result.stderr)

    def test_wrapper_marker_requires_complete_prod_configuration(self):
        """Under the wrapper marker, missing data or a foreign execution owner fails closed."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            good = write_config(root, placeholder_config(), name="good.env")
            marker = {WRAPPER_MARKER: "true", "SPRING_PROFILES_ACTIVE": "prod",
                      "SPRING_FLYWAY_ENABLED": "false",
                      "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED": "false"}

            result = source_dev_script(":", {"DEV_ENV_FILE": str(good), **marker})
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            no_file = source_dev_script(":", marker)
            self.assertNotEqual(0, no_file.returncode)
            self.assertIn("requires DEV_ENV_FILE", no_file.stderr)

            omitted = write_config(
                root,
                placeholder_config(omit=("KK_STUDIO_STORAGE_S3_SECRET_KEY",)),
                name="omitted.env",
            )
            result = source_dev_script(":", {"DEV_ENV_FILE": str(omitted), **marker})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("KK_STUDIO_STORAGE_S3_SECRET_KEY", result.stderr)

            emptied = write_config(
                root,
                placeholder_config(empty=("KK_STUDIO_DB_PASSWORD",)),
                name="emptied.env",
            )
            result = source_dev_script(":", {"DEV_ENV_FILE": str(emptied), **marker})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("KK_STUDIO_DB_PASSWORD", result.stderr)

            for override, expected in (
                ({"SPRING_PROFILES_ACTIVE": "e2e"}, "SPRING_PROFILES_ACTIVE=prod"),
                ({"SPRING_FLYWAY_ENABLED": "true"}, "SPRING_FLYWAY_ENABLED=false"),
                ({"KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED": "true"},
                 "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false"),
            ):
                overrides = {"DEV_ENV_FILE": str(good), **marker}
                overrides.update(override)
                result = source_dev_script(":", overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)

            workers_unset = dict(marker)
            workers_unset.pop("KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED")
            result = source_dev_script(":", {"DEV_ENV_FILE": str(good), **workers_unset})
            self.assertNotEqual(0, result.returncode)
            self.assertIn("KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED=false", result.stderr)

    def test_config_values_are_never_printed_on_success_or_failure(self):
        """Every success and failure path keeps the external data-plane values out of output."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            good = write_config(root, placeholder_config(optional=True), name="good.env")
            scenarios = [
                {"DEV_ENV_FILE": str(good)},
                {"DEV_ENV_FILE": str(good), WRAPPER_MARKER: "true",
                 "SPRING_PROFILES_ACTIVE": "prod", "SPRING_FLYWAY_ENABLED": "false",
                 "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED": "false"},
                {"DEV_ENV_FILE": str(root / "missing.env"), WRAPPER_MARKER: "true",
                 "SPRING_PROFILES_ACTIVE": "prod", "SPRING_FLYWAY_ENABLED": "false",
                 "KK_STUDIO_HARNESS_RUNTIME_WORKERS_ENABLED": "false"},
                {"DEV_ENV_FILE": str(good), "SPRING_PROFILES_ACTIVE": "e2e",
                 "SPRING_FLYWAY_ENABLED": "true"},
            ]
            for overrides in scenarios:
                result = source_dev_script(":", overrides)
                combined = result.stdout + result.stderr
                self.assertNotIn(VALUE_PREFIX, combined, overrides)
                # 失败场景连键名都不能夹带值，成功场景也不能把值打印出来。
                self.assertNotRegex(combined, r"=fixture-value-")
            readable = write_config(root, placeholder_config(), name="readable.env", mode=0o604)
            result = source_dev_script(":", {"DEV_ENV_FILE": str(readable)})
            self.assertNotIn(VALUE_PREFIX, result.stdout + result.stderr)

    def test_template_is_placeholder_only_and_points_at_the_owner_only_path(self):
        """The tracked template must contain names only, never a host or a credential."""
        template = LOCAL_DEV_TEMPLATE.read_text()

        for key in REQUIRED_KEYS + OPTIONAL_KEYS:
            self.assertRegex(template, rf"(?m)^{key}=$", f"{key} must be an empty placeholder")
        self.assertIn(".config/kk-studio/local-dev.env", template)
        self.assertIn("DEV_ENV_FILE", template)
        self.assertNotRegex(template, r"https?://")
        self.assertNotRegex(template, r"(?m)^\s*(?:export\s+)?[A-Z_]+=\S+")


class TestDevScriptArtifactContracts(unittest.TestCase):
    """Generic scripts/dev.sh behavior that the laptop preview still depends on."""

    def test_artifact_current_binds_a_built_artifact_to_its_revision(self):
        # Intent: `DEV_SKIP_PACKAGE=true` is the only thing standing between a restart and a JAR
        # from a previous revision. Each case asserts the boolean decision of the real function.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            artifact = root / "kk-studio-web-1.0.0.jar"
            stamp = root / ".kk-studio-revision"
            revision = "9c73718d94be910308e6b075dd70ab7075b2ae46"

            def artifact_current(expected):
                return source_dev_script(
                    "artifact_current"
                    f" {shlex.quote(str(artifact))}"
                    f" {shlex.quote(str(stamp))}"
                    f" {shlex.quote(expected)}",
                    {"DEV_WORK_DIR": str(root / "runtime")},
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
        # Intent: the stamp written at the previous build is what decides whether Maven may be
        # skipped; shell wiring must call the same predicate, otherwise the helper could be
        # correct while the lifecycle still serves stale artifacts.
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
        # `mvn clean` removes the JAR and the stamp together, so the stamp can never claim that
        # a deleted artifact is current.
        self.assertIn(
            'BACKEND_JAR_REVISION_STAMP="$APP_HOME/web/target/.kk-studio-revision"',
            DEV_SCRIPT.read_text(),
        )
        revision_fn = function_body(DEV_SCRIPT, "current_revision")
        self.assertIn('git -C "$APP_HOME" rev-parse HEAD', revision_fn)
        self.assertIn("|| true", revision_fn)

    def test_ensure_frontend_deps_reinstalls_only_when_the_lock_changed(self):
        # Intent: `npm install` is only correct for a missing tree; an upgraded
        # `package-lock.json` needs `npm ci`, otherwise a restarted preview keeps dependency
        # versions the current lock file no longer allows. Each case runs the real function
        # against a recorder `npm`, so the assertions are about issued commands.
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
                environment = clean_environment()
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
                    env=environment,
                    text=True,
                    capture_output=True,
                    check=False,
                )

            def calls():
                recorded = npm_log.read_text().splitlines() if npm_log.exists() else []
                if npm_log.exists():
                    npm_log.unlink()
                return recorded

            def lock_digest():
                return subprocess.run(
                    ["git", "hash-object", "package-lock.json"],
                    cwd=frontend,
                    text=True,
                    capture_output=True,
                    check=True,
                ).stdout.strip()

            # A missing tree is a plain install, and the fresh tree is stamped.
            result = run_ensure()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["install"], calls())
            self.assertTrue(node_modules.is_dir())
            first_digest = stamp.read_text().strip()
            self.assertRegex(first_digest, r"^[0-9a-f]{40}$")
            self.assertEqual(
                lock_digest(),
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

            # The explicit skip switch stays a total opt-out: with a changed lock and even with
            # no tree at all, the lifecycle must not invoke npm.
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
            # `npm` is what recreates the skipped tree, so the stamp it owned is gone too; the
            # failed-install case below starts from an explicit, known stamp value.
            node_modules.mkdir()
            previous_digest = "0" * 40
            stamp.write_text(f"{previous_digest}\n")

            # A failed install must not leave a fresh-looking stamp, otherwise the next start
            # would reuse a tree that npm never finished rebuilding.
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
                    env={**clean_environment(), "DEV_WORK_DIR": str(root / "runtime")},
                ).returncode,
                "a missing lock file must be reported by status, not by a fabricated digest",
            )


class TestRemovedDevTopology(unittest.TestCase):
    """The NAS source-build node and its image must not come back."""

    ALLOWED_SPELLING_FILES = {
        # 本文件持有这些标识符本身。
        "scripts/e2e/tests/test_local_dev_workflow.py",
    }
    REMOVED_DAEMON_FLAG_ALLOWED = {
        "harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfigTest.java",
        "harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonMainTest.java",
        "scripts/e2e/tests/test_local_dev_workflow.py",
        "scripts/e2e/tests/test_daemon_bootstrap_contracts.py",
    }

    def test_no_live_reference_to_the_dev_node_image_or_workspace(self):
        """`deploy/dev`, `kk-studio-dev*` and `KK_STUDIO_DEV_*` must have no live reference."""
        matches = git_grep(
            ("deploy/dev", "kk-studio-dev", "vps-kk-studio-dev", "KK_STUDIO_DEV_"),
        )
        unexpected = [
            (path, line_number)
            for path, line_number, _ in matches
            if path not in self.ALLOWED_SPELLING_FILES
        ]
        self.assertEqual([], unexpected)

    def test_no_startup_fixture_passes_the_removed_daemon_environment_root(self):
        """The removed daemon flag may only survive as a historical rejection test."""
        matches = git_grep(("--environment-root",))
        unexpected = [
            (path, line_number)
            for path, line_number, _ in matches
            if path not in self.REMOVED_DAEMON_FLAG_ALLOWED
        ]
        self.assertEqual([], unexpected)

    def test_database_rebuild_has_no_default_follower_container(self):
        """One app container is the norm; optional followers must be requested explicitly."""
        script = (REPOSITORY_ROOT / "scripts" / "operations" / "rebuild-database.sh").read_text()

        self.assertIn("FOLLOWER_CONTAINER_NAMES=${KK_STUDIO_REBUILD_FOLLOWER_CONTAINERS:-}", script)
        self.assertNotRegex(script, r"KK_STUDIO_REBUILD_FOLLOWER_CONTAINERS:-[^\s}]")
        self.assertIn("default: (none)", script)


if __name__ == "__main__":
    unittest.main()
