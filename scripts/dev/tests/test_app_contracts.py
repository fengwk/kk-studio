"""Permanent guards for the dev app lifecycle: clean package, runtime work dir, credential scrubbing."""

import os
import re
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path


def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


def clean_environment():
    """Return an environment with no inherited repository-root override or data-plane input."""
    environment = dict(os.environ)
    environment.pop("KK_STUDIO_REPO_ROOT", None)
    environment.pop("SHARED_PREVIEW_ENV_FILE", None)
    return environment


REPOSITORY_ROOT = repository_root()
APP_SCRIPT = REPOSITORY_ROOT / "scripts" / "dev" / "app.sh"
MATRIX_RUNNER = REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "e2e" / "run-matrix.mjs"


def function_body(script_path, function_name):
    """Extract a top-level shell function body for command-contract assertions."""
    source = script_path.read_text()
    match = re.search(
        rf"(?ms)^{re.escape(function_name)}\(\) \{{\n(?P<body>.*?)^\}}$",
        source,
    )
    if match is None:
        raise AssertionError(f"missing shell function {function_name} in {script_path}")
    return re.sub(r"\\\s*\n\s*", " ", match.group("body"))


class TestDevAppPackaging(unittest.TestCase):
    """Deleted classes must not survive a dev-requested backend rebuild."""

    def test_dev_backend_start_uses_clean_package(self):
        body = function_body(APP_SCRIPT, "package_backend")
        self.assertRegex(body, r"(?:\bmvn\b|\brun_maven\b).*clean\b\s+\bpackage\b")

    def test_dev_runtime_work_directory_survives_maven_clean(self):
        """Build output may be cleaned; the managed runtime directory must not live under target."""
        self.assertIn(
            'WORK_DIR=${DEV_WORK_DIR:-"$REPO_ROOT/runtime/dev"}',
            APP_SCRIPT.read_text(),
        )
        self.assertIn("path.join(REPO_ROOT, 'runtime', 'dev')", MATRIX_RUNNER.read_text())


class TestDevAppRootResolution(unittest.TestCase):
    """The dev lifecycle must locate the checkout by discovery, never by a fixed directory depth."""

    def test_repository_root_discovery_is_depth_independent_and_overridable(self):
        """Intent: `../..` breaks as soon as the entry moves; discovery plus the override must win.

        The same real script body is sourced from three nested depths, so the assertion is about
        the shipped implementation rather than a copy of the resolution logic.
        """
        source = APP_SCRIPT.read_text()
        self.assertNotIn("$SCRIPT_HOME/../..", source)
        self.assertNotIn('"$SCRIPT_HOME/../../"', source)

        with tempfile.TemporaryDirectory() as temporary:
            checkout = Path(temporary).resolve() / "checkout"
            (checkout / ".git").mkdir(parents=True)
            # 深度 4 与真实 scripts/dev/app.sh 的深度 2 不同：任何固定层级推导都会指向错误目录。
            nested = checkout / "a" / "b" / "c" / "d" / "app.sh"
            nested.parent.mkdir(parents=True)
            nested.write_text(source)

            result = subprocess.run(
                ["bash", "-c", f'source {shlex.quote(str(nested))}\nprintf "%s\\n" "$REPO_ROOT"'],
                cwd="/",
                env=clean_environment(),
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(checkout, Path(result.stdout.strip()).resolve())

            override = Path(temporary).resolve() / "elsewhere"
            override.mkdir()
            result = subprocess.run(
                ["bash", "-c", f'source {shlex.quote(str(nested))}\nprintf "%s\\n" "$REPO_ROOT"'],
                cwd="/",
                env={**clean_environment(), "KK_STUDIO_REPO_ROOT": str(override)},
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(override, Path(result.stdout.strip()).resolve())

    def test_unresolvable_root_fails_closed(self):
        """A checkout that cannot be located must fail before any lifecycle command runs."""
        source = APP_SCRIPT.read_text()
        with tempfile.TemporaryDirectory() as temporary:
            orphan = Path(temporary).resolve() / "snapshot" / "scripts" / "dev" / "app.sh"
            orphan.parent.mkdir(parents=True)
            orphan.write_text(source)

            result = subprocess.run(
                ["bash", "-c", f'source {shlex.quote(str(orphan))}'],
                cwd="/",
                env=clean_environment(),
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("cannot locate the kk-studio repository root", result.stderr)


class TestDevRuntimeCredentialScrubbing(unittest.TestCase):
    """The dev app lifecycle must never leak host provider credentials into long-lived processes."""

    def test_long_lived_runtime_processes_scrub_all_test_inputs(self):
        """The dev lifecycle owns its own scrubber and its own four-pair sync call."""
        script_path = APP_SCRIPT
        script = script_path.read_text()
        start_all = function_body(script_path, "start_all")
        sync = function_body(script_path, "sync_e2e_provider_credentials")
        e2e_names = [
            "TEST_GOOGLE_BASE_URL",
            "TEST_GOOGLE_API_KEY",
            "TEST_OPENAI_BASE_URL",
            "TEST_OPENAI_API_KEY",
            "TEST_ANTHROPIC_BASE_URL",
            "TEST_ANTHROPIC_API_KEY",
            "TEST_DEEPSEEK_BASE_URL",
            "TEST_DEEPSEEK_API_KEY",
        ]

        for name in e2e_names:
            self.assertIn(f'{name}="${{{name}-}}"', sync)
        # MiniMax 凭据属于 reliability 矩阵，不得进入本机 dev 数据面。
        self.assertNotIn("TEST_MINIMAX_BASE_URL=", sync)
        self.assertNotIn("TEST_MINIMAX_API_KEY=", sync)
        self.assertIn('"${test_env_unsets[@]}"', start_all)

        scrubber = function_body(script_path, "test_env_unset_args")
        self.assertIn("TEST_*)", scrubber)
        self.assertIn("printf '%s\\0' -u", scrubber)
        self.assertIn("compgen -e", scrubber)
        self.assertNotIn("< <(env)", scrubber)
        self.assertNotIn("TEST_GOOGLE", scrubber)

    def test_test_environment_unset_args_covers_unknown_names(self):
        """The dev scrubber must emit every TEST_* name and leave unrelated inputs alone."""
        env = os.environ.copy()
        env.update(
            {
                "TEST_FUTURE_PROVIDER_API_KEY": "future-secret",
                "TEST_API_KEY": "generic-secret",
                "UNRELATED_API_KEY": "keep",
            }
        )
        result = subprocess.run(
            [
                "bash",
                "-c",
                (
                    f"source {APP_SCRIPT} status >/dev/null\n"
                    "mapfile -d '' -t args < <(test_env_unset_args)\n"
                    "printf '%s\\n' \"${args[@]}\""
                ),
            ],
            cwd=REPOSITORY_ROOT,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        args = result.stdout.splitlines()
        pairs = set(zip(args[0::2], args[1::2]))

        self.assertIn(("-u", "TEST_FUTURE_PROVIDER_API_KEY"), pairs)
        self.assertIn(("-u", "TEST_API_KEY"), pairs)
        self.assertNotIn(("-u", "UNRELATED_API_KEY"), pairs)


if __name__ == "__main__":
    unittest.main()
