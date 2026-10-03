"""Execute isolated lifecycle scripts with fake tools, never a real Docker daemon."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


def repository_root():
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root")


ROOT = repository_root()
CASES = (
    ("smoke/offline-chat.sh", [], 1),
    ("smoke/offline-chat.sh", ["--with-app"], 1),
    ("performance/run.sh", [], 1),
    ("performance/run.sh", ["--skip-build"], 0),
    ("e2e/distributed.sh", ["up"], 2),
    ("e2e/distributed.sh", ["up", "--skip-build"], 0),
)

# Log argv as JSON, not shell text: option boundaries and service selection matter.
DOCKER = """#!/usr/bin/env python3
import json
import os
import sys
args = sys.argv[1:]
with open(os.environ["FAKE_DOCKER_LOG"], "a") as stream:
    stream.write(json.dumps(args) + "\\n")
if args[0] == "compose" and "build" in args:
    if args[args.index("build") + 1:] != ["minio-init"]:
        sys.exit(91)
    if os.environ.get("FAIL_MC_BUILD") == "true":
        sys.exit(42)
if args[0] == "compose" and "exec" in args and "postgres" in args:
    print("1")
"""


def run_entrypoint(script, arguments, fail=False):
    with tempfile.TemporaryDirectory(prefix="kk-isolated-build-contract-") as temporary:
        directory = Path(temporary)
        bin_dir = directory / "bin"
        bin_dir.mkdir()
        # Docker's shebang uses the real interpreter; only shell-launched python3
        # is stubbed to avoid executing the smoke's embedded HTTP/API assertions.
        tools = {
            "docker": DOCKER.replace(
                "#!/usr/bin/env python3", f"#!{sys.executable}", 1
            ),
            "node": "#!/usr/bin/env bash\nexit 0\n",
            "python3": '#!/usr/bin/env bash\n'
            'if [[ "${1:-}" == "-c" && "${2:-}" == *"rows ="* ]]; then\n'
            '  printf "READY\\n"\n'
            'fi\n',
            "curl": "#!/usr/bin/env bash\nexit 0\n",
        }
        for name, content in tools.items():
            executable = bin_dir / name
            executable.write_text(content)
            executable.chmod(0o755)
        log = directory / "docker.jsonl"
        env = os.environ.copy()
        # Keep proxy credentials out of mocked argv/logs and make tests host-independent.
        for key in list(env):
            if "PROXY" in key.upper() or key.startswith(
                ("CANVAS_TEST_BUILD_", "KK_STUDIO_BUILD_", "KK_STUDIO_MAVEN_BUILD_")
            ):
                env.pop(key)
        env.update(
            PATH=f"{bin_dir}{os.pathsep}{env.get('PATH', '')}",
            KK_STUDIO_REPO_ROOT=str(ROOT),
            FAKE_DOCKER_LOG=str(log),
            FAIL_MC_BUILD=str(fail).lower(),
        )
        argv = ["bash", str(ROOT / "scripts/dev/verify" / script), *arguments]
        if script == "performance/run.sh":
            argv += ["--duration-seconds", "1", "--report-root", str(directory / "report")]
        result = subprocess.run(
            argv, cwd=directory, env=env, text=True, capture_output=True, timeout=15
        )
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        return result, calls


class TestIsolatedDependencyBuilds(unittest.TestCase):
    def test_mc_build_precedes_start_even_when_source_builds_are_skipped(self):
        """A clean host always builds mc, without rebuilding app/daemon via Compose."""
        for script, arguments, source_builds in CASES:
            with self.subTest(script=script, arguments=arguments):
                result, calls = run_entrypoint(script, arguments)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                builds = [
                    i for i, call in enumerate(calls)
                    if "build" in call and call[0] == "compose"
                ]
                starts = [i for i, call in enumerate(calls) if "up" in call]
                self.assertEqual(1, len(builds))
                self.assertEqual(1, len(starts))
                self.assertEqual(["build", "minio-init"], calls[builds[0]][-2:])
                self.assertLess(builds[0], starts[0])
                self.assertNotIn("--build", calls[starts[0]])
                if script != "smoke/offline-chat.sh" or "--with-app" in arguments:
                    self.assertIn("--no-build", calls[starts[0]])
                self.assertEqual(
                    source_builds, sum(call[0] == "build" for call in calls)
                )
                if script == "smoke/offline-chat.sh":
                    init = next(
                        i for i, call in enumerate(calls)
                        if "run" in call and call[-1] == "minio-init"
                    )
                    self.assertLess(builds[0], init)

    def test_failed_mc_build_blocks_all_startup_paths(self):
        """A dependency build failure propagates, with smoke/performance cleanup intact."""
        for script, arguments, _ in CASES:
            with self.subTest(script=script, arguments=arguments):
                result, calls = run_entrypoint(script, arguments, fail=True)
                self.assertEqual(42, result.returncode, result.stdout + result.stderr)
                self.assertFalse(any("up" in call or "run" in call for call in calls))
                if script != "e2e/distributed.sh":
                    self.assertEqual("compose", calls[-1][0])
                    self.assertIn("down", calls[-1])
                    self.assertIn("--volumes", calls[-1])

    def test_skip_build_help_names_source_images_only(self):
        """Public shell help must not promise to skip the dependency build."""
        for script in ("performance/run.sh", "e2e/distributed.sh"):
            result = subprocess.run(
                ["bash", str(ROOT / "scripts/dev/verify" / script), "--help"],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(0, result.returncode)
            self.assertIn("shared MinIO client is still built", result.stdout)


if __name__ == "__main__":
    unittest.main()
