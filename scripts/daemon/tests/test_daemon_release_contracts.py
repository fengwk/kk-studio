"""Permanent contract regression for the Environment Daemon GitHub Release path.

Covers the single asset-staging implementation (``scripts/daemon/prepare-release.sh``)
and its workflow (``.github/workflows/daemon-release.yml``). The dynamic cases build a real
(but tiny) JAR with the local JDK and run the script inside a throwaway repository, so they
need neither the Maven reactor, a network connection, nor the real 12 MB Daemon artifact;
the executable-bit and interface assertions on the real artifact are the only things beyond
this test's reach.
"""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile

def repository_root():
    """Resolve the checkout root: KK_STUDIO_REPO_ROOT, else the enclosing worktree."""
    override = os.environ.get("KK_STUDIO_REPO_ROOT")
    if override:
        return Path(override).resolve()
    for candidate in Path(__file__).resolve().parents:
        if (candidate / ".git").exists():
            return candidate
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
RELEASE_SCRIPT = REPOSITORY_ROOT / "scripts" / "daemon" / "prepare-release.sh"
RELEASE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "daemon-release.yml"
LICENSE_FILE = REPOSITORY_ROOT / "LICENSE"
THIRD_PARTY_NOTICES = REPOSITORY_ROOT / "harness" / "daemon" / "THIRD_PARTY_NOTICES"

SAFE_TAG = "v9.9.9"
# Tag 直接进入资产文件名，所以任何越出 `[0-9A-Za-z._-]` 的输入都必须在删除目录之前失败。
UNSAFE_TAGS = (
    "",
    "9.9.9",
    "V9.9.9",
    "v",
    "v1.0.0/../../etc",
    "v1.0.0/..",
    "v1 0 0",
    "v1.0.0;rm -rf /",
    "v1.0.0$(id)",
    "v1.0.0`id`",
    "v1.0.0*",
    "v1.0.0?",
    "v1.0.0|x",
    "v1.0.0\nv2.0.0",
    "v1.0.0#",
    "v1.0.0:",
    "--tag",
    "-v1.0.0",
    "../v1.0.0",
    "release/v1.0.0",
)
# 归档根必须同时含入口类与三个直接依赖类：thin JAR 与 Spring Boot 布局都会在这里失败。
REQUIRED_JAR_ENTRIES = (
    "fun/fengwk/kkstudio/harness/daemon/DaemonMain.class",
    "okhttp3/OkHttpClient.class",
    "org/eclipse/jgit/ignore/FastIgnoreRule.class",
    "com/fasterxml/jackson/databind/ObjectMapper.class",
    "META-INF/THIRD_PARTY_NOTICES",
)
DAEMON_MAIN_CLASS = "fun.fengwk.kkstudio.harness.daemon.DaemonMain"
FIXTURE_PROJECT_VERSION = "9.9.9"
EXPECTED_METADATA_KEYS = (
    "schemaVersion",
    "tag",
    "commit",
    "sourceDateEpoch",
    "projectVersion",
    "minimumJava",
    "mainClass",
    "artifact",
    "artifactSha256",
)

GIT_FIXTURE_ENV = {
    "GIT_CONFIG_GLOBAL": "/dev/null",
    "GIT_CONFIG_SYSTEM": "/dev/null",
    "GIT_AUTHOR_NAME": "kk-studio test",
    "GIT_AUTHOR_EMAIL": "test@kk-studio.invalid",
    "GIT_COMMITTER_NAME": "kk-studio test",
    "GIT_COMMITTER_EMAIL": "test@kk-studio.invalid",
}


def resolve_jdk_home():
    """Return one JDK that has both javac and jar, or None when unavailable."""
    candidates = []
    for name in ("JAVA_HOME_21", "JAVA_HOME"):
        value = os.environ.get(name)
        if value:
            candidates.append(Path(value))
    javac = shutil.which("javac")
    if javac:
        candidates.append(Path(javac).resolve().parents[1])
    for candidate in candidates:
        if (candidate / "bin" / "javac").is_file() and (candidate / "bin" / "jar").is_file():
            return candidate
    return None


JDK_HOME = resolve_jdk_home()


def run(command, cwd=None, env=None, check=False):
    """Run one command with an inherited environment plus explicit overrides."""
    environment = dict(os.environ)
    environment.update(env or {})
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        capture_output=True,
        check=False,
        env=environment,
    )
    if check and result.returncode != 0:
        raise AssertionError(f"{' '.join(command)} failed: {result.stdout}{result.stderr}")
    return result


def git(directory, *arguments, check=True):
    """Run one git command against a fixture repository without ambient config."""
    result = run(["git", *arguments], cwd=directory, env=GIT_FIXTURE_ENV)
    if check and result.returncode != 0:
        raise AssertionError(f"git {' '.join(arguments)} failed: {result.stderr}")
    return result


def write_fixture_jar(jdk_home, target, *, project_version=FIXTURE_PROJECT_VERSION, entries=None,
                      main_class=DAEMON_MAIN_CLASS, main_body=None, executable=True):
    """Build a tiny real JAR shaped like the shaded Daemon artifact.

    ``main_body`` defaults to a class that prints ``kk-studio-daemon <version>`` for
    ``--version``; passing a body that exits non-zero models a JAR whose entry point is
    present but broken. ``executable=False`` omits ``Main-Class``, which is what a thin
    library JAR looks like.
    """
    source_root = target.parent / f"{target.stem}-src"
    source_root.mkdir(parents=True, exist_ok=True)
    package_dir = source_root / Path(*main_class.split(".")[:-1])
    package_dir.mkdir(parents=True, exist_ok=True)
    (package_dir / f"{main_class.rsplit('.', 1)[-1]}.java").write_text(
        f"package {main_class.rsplit('.', 1)[0]};\n"
        f"public final class {main_class.rsplit('.', 1)[-1]} {{\n"
        "  public static void main(String[] args) {\n"
        + (
            main_body
            if main_body is not None
            else f'    System.out.println("kk-studio-daemon {project_version}");\n'
        )
        + "  }\n"
        "}\n",
        encoding="utf-8",
    )
    classes = target.parent / f"{target.stem}-classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    run(
        [str(jdk_home / "bin" / "javac"), "-d", str(classes), str(package_dir / f"{main_class.rsplit('.', 1)[-1]}.java")],
        check=True,
    )

    manifest_lines = ["Manifest-Version: 1.0"]
    if executable:
        manifest_lines.append(f"Main-Class: {main_class}")
    manifest_lines.append(f"Implementation-Version: {project_version}")
    manifest = ("\r\n".join(manifest_lines) + "\r\n\r\n").encode("utf-8")

    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", manifest)
        written = {"META-INF/MANIFEST.MF"}
        for source in sorted(classes.rglob("*.class")):
            name = source.relative_to(classes).as_posix()
            archive.write(source, name)
            written.add(name)
        for entry in REQUIRED_JAR_ENTRIES if entries is None else entries:
            if entry in written:
                continue
            # 依赖类只需存在：脚本只按归档条目校验，`--version` 不会加载它们。
            archive.writestr(entry, b"")
    return target


def build_fixture_repository(root, jar_source):
    """Materialize a throwaway repository that mirrors the real release inputs.

    The script discovers its repository root from its own location, so a copy under the same
    ``scripts/daemon`` path exercises exactly the shipped implementation while the fixture
    JAR stands in for the Maven-built artifact.
    """
    root = Path(root)
    (root / "scripts" / "daemon").mkdir(parents=True)
    script = root / "scripts" / "daemon" / RELEASE_SCRIPT.name
    shutil.copy2(RELEASE_SCRIPT, script)
    script.chmod(script.stat().st_mode | stat.S_IXUSR)
    shutil.copy2(LICENSE_FILE, root / "LICENSE")
    (root / "harness" / "daemon" / "target").mkdir(parents=True)
    shutil.copy2(THIRD_PARTY_NOTICES, root / "harness" / "daemon" / "THIRD_PARTY_NOTICES")
    if jar_source is not None:
        shutil.copy2(jar_source, root / "harness" / "daemon" / "target" / "kk-studio-daemon.jar")
    git(root, "init", "-q", "-b", "main")
    git(root, "add", "-A")
    git(root, "commit", "-qm", "fixture")
    return root, script


def stage(script, root, *arguments):
    """Invoke the staging script with one explicit JDK and the fixture repository."""
    return run(["bash", str(script), *arguments], cwd=root, env={"JAVA_HOME_21": str(JDK_HOME)})


@unittest.skipUnless(JDK_HOME, "a JDK with javac and jar is required for real-JAR fixtures")
class TestDaemonReleaseStaging(unittest.TestCase):
    """The staging script must fail closed on unsafe inputs and stage exactly five assets."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture_jar = write_fixture_jar(
            JDK_HOME, self.root / "fixture" / "kk-studio-daemon.jar"
        )
        self.fixture_root, self.script = build_fixture_repository(
            self.root / "repo", self.fixture_jar
        )
        self.release_dir = self.fixture_root / "harness" / "daemon" / "target" / "release"

    def test_script_is_executable_and_shell_syntax_clean(self):
        self.assertTrue(os.access(RELEASE_SCRIPT, os.X_OK), "release script must be executable")
        source = RELEASE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("set -euo pipefail", source)
        self.assertEqual(0, run(["bash", "-n", str(RELEASE_SCRIPT)]).returncode)

    def test_output_is_fixed_to_the_daemon_target_release_directory(self):
        """The script accepts no output path, so recursive deletion cannot target caller input."""
        source = RELEASE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            'RELEASE_DIR="$REPO_ROOT/harness/daemon/target/release"',
            source,
        )
        self.assertEqual(1, len(re.findall(r"(?m)^RELEASE_DIR=", source)))
        self.assertIn('rm -rf -- "$RELEASE_DIR"', source)
        self.assertNotIn("[output-dir]", source)

    def test_unsafe_tags_fail_before_any_output_directory_is_touched(self):
        """文件名与目录名都来自 tag，所以字符集之外的输入必须在暂存之前失败。"""
        for tag in UNSAFE_TAGS:
            with self.subTest(tag=tag):
                result = stage(self.script, self.fixture_root, tag)
                self.assertNotEqual(0, result.returncode, f"tag {tag!r} must be rejected")
                self.assertRegex(result.stderr, r"release tag|Usage:")
                self.assertFalse(self.release_dir.exists(), f"tag {tag!r} created assets")

    def assert_jar_is_rejected(self, jar_path, expected_message):
        self.fixture_root, self.script = build_fixture_repository(
            self.root / f"repo-{expected_message}", jar_path
        )
        release_dir = self.fixture_root / "harness" / "daemon" / "target" / "release"
        result = stage(self.script, self.fixture_root, SAFE_TAG)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(expected_message, result.stderr)
        self.assertFalse(release_dir.exists())

    def test_malformed_jar_is_rejected(self):
        malformed = self.root / "malformed.jar"
        malformed.write_bytes(b"this is not a zip archive")
        self.assert_jar_is_rejected(malformed, "not a readable archive")

    def test_thin_jar_without_shaded_dependencies_is_rejected(self):
        thin = write_fixture_jar(
            JDK_HOME, self.root / "thin" / "kk-studio-daemon.jar", entries=()
        )
        self.assert_jar_is_rejected(thin, "missing a top-level entry")

    def test_jar_with_nested_dependency_layout_is_rejected(self):
        """Spring Boot 布局把依赖放在 BOOT-INF/lib，因此归档根校验必须失败。"""
        nested = write_fixture_jar(
            JDK_HOME,
            self.root / "boot" / "kk-studio-daemon.jar",
            entries=tuple(f"BOOT-INF/lib/{entry}" for entry in REQUIRED_JAR_ENTRIES),
        )
        self.assert_jar_is_rejected(nested, "missing a top-level entry")

    def test_jar_without_a_main_class_is_rejected(self):
        thin = write_fixture_jar(
            JDK_HOME, self.root / "no-main" / "kk-studio-daemon.jar", executable=False
        )
        self.assert_jar_is_rejected(thin, "Main-Class")

    def test_jar_whose_entry_point_fails_is_rejected(self):
        broken = write_fixture_jar(
            JDK_HOME,
            self.root / "broken" / "kk-studio-daemon.jar",
            main_body="    System.exit(3);\n",
        )
        self.assert_jar_is_rejected(broken, "is not executable")

    def test_missing_daemon_jar_is_rejected_before_staging(self):
        fixture_root, script = build_fixture_repository(self.root / "repo-empty", None)
        result = stage(script, fixture_root, SAFE_TAG)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("daemon JAR not found", result.stderr)
        self.assertFalse((fixture_root / "harness" / "daemon" / "target" / "release").exists())

    def test_non_21_jdk_is_rejected_with_an_explicit_diagnostic(self):
        """发布物以 JDK 21 为目标，因此校验 JDK 也必须正好是 21，而不是任何可运行的 JVM。"""
        source = RELEASE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("JDK 21 is required", source)
        fake_home = self.root / "jdk-17"
        (fake_home / "bin").mkdir(parents=True)
        for tool in ("java", "jar"):
            stub = fake_home / "bin" / tool
            stub.write_text('#!/usr/bin/env bash\necho \'openjdk version "17.0.9" 2023-10-17\' >&2\n')
            stub.chmod(0o755)
        result = run(
            ["bash", str(self.script), SAFE_TAG],
            cwd=self.fixture_root,
            env={"JAVA_HOME_21": str(fake_home)},
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("JDK 21 is required", result.stderr)
        self.assertFalse(self.release_dir.exists())

    def test_successful_staging_produces_exactly_five_deterministic_assets(self):
        result = stage(self.script, self.fixture_root, SAFE_TAG)
        self.assertEqual(0, result.returncode, result.stderr)

        artifact = f"kk-studio-daemon-{SAFE_TAG}.jar"
        assets = sorted(path.name for path in self.release_dir.iterdir())
        self.assertEqual(
            sorted(
                [
                    artifact,
                    f"{artifact}.sha256",
                    f"kk-studio-daemon-{SAFE_TAG}.json",
                    "LICENSE",
                    "THIRD_PARTY_NOTICES",
                ]
            ),
            assets,
        )

        # JAR 必须是原始构建产物的逐字节拷贝。
        self.assertEqual(
            hashlib.sha256(self.fixture_jar.read_bytes()).hexdigest(),
            hashlib.sha256((self.release_dir / artifact).read_bytes()).hexdigest(),
        )
        # 校验文件必须是标准 `sha256sum -c` 输入，且能验证自己的目标。
        checksum_text = (self.release_dir / f"{artifact}.sha256").read_text(encoding="utf-8")
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\n]+)\n", checksum_text)
        self.assertIsNotNone(match, checksum_text)
        self.assertEqual(artifact, match.group(2))
        self.assertEqual(
            hashlib.sha256((self.release_dir / artifact).read_bytes()).hexdigest(),
            match.group(1),
        )
        verified = run(
            ["sha256sum", "-c", f"{artifact}.sha256"], cwd=self.release_dir
        )
        self.assertEqual(0, verified.returncode, verified.stderr)

        metadata = json.loads(
            (self.release_dir / f"kk-studio-daemon-{SAFE_TAG}.json").read_text(encoding="utf-8")
        )
        self.assertEqual(list(EXPECTED_METADATA_KEYS), list(metadata))
        self.assertEqual(1, metadata["schemaVersion"])
        self.assertEqual(SAFE_TAG, metadata["tag"])
        self.assertEqual(git(self.fixture_root, "rev-parse", "HEAD").stdout.strip(), metadata["commit"])
        self.assertEqual(
            int(git(self.fixture_root, "show", "-s", "--format=%ct", "HEAD").stdout.strip()),
            metadata["sourceDateEpoch"],
        )
        self.assertEqual(FIXTURE_PROJECT_VERSION, metadata["projectVersion"])
        self.assertEqual(21, metadata["minimumJava"])
        self.assertEqual(DAEMON_MAIN_CLASS, metadata["mainClass"])
        self.assertEqual(artifact, metadata["artifact"])
        self.assertEqual(match.group(1), metadata["artifactSha256"])
        # 元数据确定性：不含时钟或环境字段，重复暂存必须逐字节一致。
        self.assertEqual(
            (self.release_dir / f"kk-studio-daemon-{SAFE_TAG}.json").read_bytes(),
            json.dumps(metadata, indent=2).encode("utf-8") + b"\n",
        )

    def test_rerun_rebuilds_the_directory_without_stale_assets(self):
        """同一 tag 重跑必须重建目录且不保留额外文件。"""
        self.assertEqual(0, stage(self.script, self.fixture_root, SAFE_TAG).returncode)
        first_metadata = (self.release_dir / f"kk-studio-daemon-{SAFE_TAG}.json").read_bytes()
        (self.release_dir / "stale.txt").write_text("stale", encoding="utf-8")

        # 同一 tag 再次暂存必须与第一次逐字节一致，证明没有时钟或调用环境输入。
        self.assertEqual(0, stage(self.script, self.fixture_root, SAFE_TAG).returncode)
        self.assertFalse((self.release_dir / "stale.txt").exists())
        self.assertEqual(
            first_metadata,
            (self.release_dir / f"kk-studio-daemon-{SAFE_TAG}.json").read_bytes(),
        )

    def test_release_tag_must_match_the_packaged_version(self):
        result = stage(self.script, self.fixture_root, "v9.9.10")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected v9.9.9", result.stderr)
        self.assertFalse(self.release_dir.exists())

    def test_help_and_missing_arguments_do_not_stage_anything(self):
        help_result = stage(self.script, self.fixture_root, "--help")
        self.assertEqual(0, help_result.returncode, help_result.stderr)
        self.assertIn("Usage:", help_result.stdout)
        self.assertIn("target/release", help_result.stdout)

        no_arguments = stage(self.script, self.fixture_root)
        self.assertNotEqual(0, no_arguments.returncode)
        self.assertFalse(self.release_dir.exists())

        too_many = stage(self.script, self.fixture_root, SAFE_TAG, "extra")
        self.assertNotEqual(0, too_many.returncode)


def workflow_run_block(workflow, step_name):
    """Extract one step's `run: |` script, dedented, so it can be executed for real.

    Reading the YAML with a real parser would pull in a dependency the repository does not
    otherwise need, and the block scalar is simple enough to locate positionally.
    """
    lines = workflow.splitlines()
    start = next(
        index
        for index, line in enumerate(lines)
        if line.strip() == f"- name: {step_name}"
    )
    script_start = None
    for index in range(start, len(lines)):
        if re.fullmatch(r"\s+run: \|", lines[index]):
            script_start = index + 1
            break
    if script_start is None:
        raise AssertionError(f"step {step_name!r} has no 'run: |' block")

    indentation = None
    body = []
    for line in lines[script_start:]:
        if line.strip():
            current = len(line) - len(line.lstrip(" "))
            if indentation is None:
                indentation = current
                if current < 2:
                    raise AssertionError(f"step {step_name!r} block is not indented")
            elif current < indentation:
                break
        body.append(line[indentation:] if indentation else line)
    while body and not body[-1].strip():
        body.pop()
    return "\n".join(body) + "\n"


class TestDaemonReleaseWorkflow(unittest.TestCase):
    """The workflow must gate publication on main, quality gates and the staged asset set."""

    def setUp(self):
        self.workflow = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.gh_log = self.root / "gh.log"
        stub_dir = self.root / "bin"
        stub_dir.mkdir()
        # 只替换 `gh`：脚本逻辑本身照常执行，上传调用被记录下来而不是真的发出。
        (stub_dir / "gh").write_text(
            "#!/usr/bin/env bash\n"
            'printf \'ARGV:%s\\n\' "$*" >>"$GH_STUB_LOG"\n'
            'case "$1 $2" in\n'
            '  "release view") [ "${GH_STUB_RELEASE_EXISTS:-false}" = true ] ;;\n'
            "  *) exit 0 ;;\n"
            "esac\n",
            encoding="utf-8",
        )
        (stub_dir / "gh").chmod(0o755)
        self.env = {
            "PATH": f"{stub_dir}:{os.environ['PATH']}",
            "GH_STUB_LOG": str(self.gh_log),
            "GH_TOKEN": "stub-token-not-a-secret",
        }

    def run_step(self, step_name, extra_env=None):
        script = self.root / f"{step_name.replace(' ', '-')}.sh"
        script.write_text(workflow_run_block(self.workflow, step_name), encoding="utf-8")
        return run(
            ["bash", str(script)],
            cwd=self.root,
            env={**self.env, **(extra_env or {})},
        )

    def prepare_staged_assets(self, names):
        """Create the staged directory the publish step validates and uploads."""
        release_dir = self.root / "staged"
        release_dir.mkdir(exist_ok=True)
        for name in names:
            (release_dir / name).write_text("staged asset", encoding="utf-8")
        return release_dir

    def expected_asset_names(self, tag=SAFE_TAG):
        return [
            "LICENSE",
            "THIRD_PARTY_NOTICES",
            f"kk-studio-daemon-{tag}.jar",
            f"kk-studio-daemon-{tag}.jar.sha256",
            f"kk-studio-daemon-{tag}.json",
        ]

    def test_trigger_is_limited_to_version_tags(self):
        self.assertRegex(
            self.workflow, r"(?m)^on:\n  push:\n    tags:\n      - 'v\*'\n"
        )
        self.assertNotIn("pull_request", self.workflow)
        self.assertNotIn("workflow_dispatch", self.workflow)
        self.assertNotIn("branches:", self.workflow)
        self.assertRegex(self.workflow, r"(?m)^permissions:\n  contents: write\n")

    def test_reruns_are_serialized_per_ref_so_partial_asset_sets_are_not_published(self):
        self.assertIn("group: daemon-release-${{ github.ref }}", self.workflow)
        self.assertIn("cancel-in-progress: false", self.workflow)

    def test_checkout_keeps_full_history_and_toolchain_is_temurin_21_with_maven_cache(self):
        self.assertRegex(self.workflow, r"uses: actions/checkout@v4\n(\s+with:\n\s+fetch-depth: 0)")
        self.assertIn("distribution: temurin", self.workflow)
        self.assertIn("java-version: ${{ env.JAVA_VERSION }}", self.workflow)
        self.assertRegex(self.workflow, r"(?m)^  JAVA_VERSION: '21'$")
        self.assertIn("cache: maven", self.workflow)

    def test_tag_and_main_ancestry_are_enforced_with_the_script_pattern(self):
        """tag 字符集由脚本唯一持有，工作流必须复用同一个模式而不是放宽它。"""
        pattern = re.search(
            r"(?m)^RELEASE_TAG_PATTERN='([^']+)'$",
            RELEASE_SCRIPT.read_text(encoding="utf-8"),
        )
        self.assertIsNotNone(pattern, "the script must publish its tag pattern")
        pattern = pattern.group(1)
        self.assertIn(pattern, self.workflow)
        self.assertRegex(self.workflow, r'\[\[ "\$\{tag\}" =~ \^v\[0-9A-Za-z\._-\]\+\$ \]\]')
        self.assertIn('if ! [[ "${tag}" =~ ', self.workflow)
        self.assertIn("git merge-base --is-ancestor", self.workflow)
        self.assertIn("refs/remotes/origin/main", self.workflow)
        self.assertIn("is not an ancestor of origin/main", self.workflow)
        # 拒绝路径必须先于构建与上传。
        self.assertLess(
            self.workflow.index("is not an ancestor of origin/main"),
            self.workflow.index("Stage release assets"),
        )

    def test_quality_gates_run_before_any_publication(self):
        gates = (
            "python3 scripts/dev/verify/repository/check-sensitive-data.py",
            "node scripts/dev/verify/repository/check.mjs",
            # 脚本测试按能力目录发现：CI 不维护能力清单，只维护所有权规则。
            "scripts/*/tests scripts/dev/verify/*/tests",
            'python3 -m unittest discover -s "${dir}"',
            'node --test "${node_files[@]}"',
            '-Dproject.build.outputTimestamp="${{ steps.tag.outputs.source_date_epoch }}"',
            'scripts/daemon/prepare-release.sh "${GITHUB_REF_NAME}"',
        )
        publish_at = self.workflow.index("Publish GitHub Release")
        for gate in gates:
            self.assertIn(gate, self.workflow, gate)
            self.assertLess(self.workflow.index(gate), publish_at, gate)

    def test_only_official_setup_actions_and_the_workflow_token_are_used(self):
        used = re.findall(r"(?m)^\s+(?:-\s+)?uses: (\S+)$", self.workflow)
        self.assertEqual(
            [
                "actions/checkout@v4",
                "actions/setup-java@v4",
                "actions/checkout@v4",
                "actions/setup-java@v4",
                "actions/setup-node@v4",
            ],
            used,
        )
        self.assertIn("persist-credentials: false", self.workflow)
        self.assertNotIn("secrets.", self.workflow)
        self.assertIn("GH_TOKEN: ${{ github.token }}", self.workflow)
        self.assertRegex(self.workflow, r"(?m)^\s+gh release ")

    def test_exactly_the_staged_assets_are_uploaded_and_reruns_clobber(self):
        self.assertIn('RELEASE_DIR: harness/daemon/target/release', self.workflow)
        self.assertIn("staged assets do not match the release contract", self.workflow)
        for expected in (
            'LICENSE',
            'THIRD_PARTY_NOTICES',
            '"kk-studio-daemon-${GITHUB_REF_NAME}.jar"',
            '"kk-studio-daemon-${GITHUB_REF_NAME}.jar.sha256"',
            '"kk-studio-daemon-${GITHUB_REF_NAME}.json"',
        ):
            self.assertIn(expected, self.workflow)
        self.assertIn('gh release create "${GITHUB_REF_NAME}"', self.workflow)
        self.assertIn("--verify-tag", self.workflow)
        self.assertIn("--generate-notes", self.workflow)
        self.assertIn('gh release upload "${GITHUB_REF_NAME}" --clobber', self.workflow)
        # 资产必须来自 RELEASE_DIR 的清单，而不是再次 glob 或写死路径。
        self.assertIn('assets+=("${RELEASE_DIR}/${name}")', self.workflow)
        self.assertNotIn("--latest", self.workflow)

    def test_publish_step_creates_a_release_with_exactly_the_five_staged_assets(self):
        """首次发布必须带上暂存的五个资产，并只在 tag 已存在时发布。"""
        release_dir = self.prepare_staged_assets(self.expected_asset_names())
        result = self.run_step(
            "Publish GitHub Release",
            {"GITHUB_REF_NAME": SAFE_TAG, "RELEASE_DIR": str(release_dir)},
        )
        self.assertEqual(0, result.returncode, result.stderr)

        calls = self.gh_log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(2, len(calls), calls)
        self.assertEqual(f"ARGV:release view {SAFE_TAG}", calls[0])
        create = calls[1]
        self.assertTrue(create.startswith(f"ARGV:release create {SAFE_TAG} "), create)
        self.assertIn("--verify-tag", create)
        self.assertIn("--generate-notes", create)
        for name in self.expected_asset_names():
            self.assertIn(f"{release_dir}/{name}", create.split())

    def test_publish_rerun_clobbers_the_same_assets_without_duplicating_the_release(self):
        """重跑只覆盖同名资产并保持 release 已发布，不创建第二次 release。"""
        release_dir = self.prepare_staged_assets(self.expected_asset_names())
        result = self.run_step(
            "Publish GitHub Release",
            {
                "GITHUB_REF_NAME": SAFE_TAG,
                "RELEASE_DIR": str(release_dir),
                "GH_STUB_RELEASE_EXISTS": "true",
            },
        )
        self.assertEqual(0, result.returncode, result.stderr)

        calls = self.gh_log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(3, len(calls), calls)
        self.assertEqual(f"ARGV:release view {SAFE_TAG}", calls[0])
        self.assertNotIn("release create", "\n".join(calls))
        self.assertTrue(calls[1].startswith(f"ARGV:release upload {SAFE_TAG} --clobber "), calls[1])
        self.assertEqual(f"ARGV:release edit {SAFE_TAG} --draft=false", calls[2])
        for name in self.expected_asset_names():
            self.assertIn(f"{release_dir}/{name}", calls[1].split())

    def test_publish_step_fails_without_uploading_when_the_asset_set_differs(self):
        """多一个或少一个资产都必须在上传之前失败，绝不发布半套资产。"""
        contract = self.expected_asset_names()
        cases = {
            "missing artifact": [name for name in contract if not name.endswith(".jar")],
            "stale previous tag": [*contract, "kk-studio-daemon-v0.0.1.jar"],
            "unexpected extra file": [*contract, "notes.txt"],
            "empty directory": [],
        }
        for label, names in cases.items():
            with self.subTest(case=label):
                shutil.rmtree(self.root / "staged", ignore_errors=True)
                release_dir = self.prepare_staged_assets(names)
                if self.gh_log.exists():
                    self.gh_log.unlink()

                result = self.run_step(
                    "Publish GitHub Release",
                    {"GITHUB_REF_NAME": SAFE_TAG, "RELEASE_DIR": str(release_dir)},
                )

                self.assertNotEqual(0, result.returncode, f"{label} must fail")
                self.assertIn("staged assets do not match the release contract", result.stderr)
                recorded = self.gh_log.read_text(encoding="utf-8") if self.gh_log.exists() else ""
                self.assertNotIn("release create", recorded)
                self.assertNotIn("release upload", recorded)

    def test_tag_step_rejects_unsafe_tags_and_commits_outside_main(self):
        """工作流的 tag 校验必须与脚本同模式，并把非 main 祖先的 tag 挡在发布之前。"""
        script = self.root / "tag-step.sh"
        script.write_text(
            workflow_run_block(self.workflow, "Require a safe version tag on a main commit"),
            encoding="utf-8",
        )
        repository = self.root / "repository"
        repository.mkdir()
        git(repository, "init", "-q", "-b", "main")
        (repository / "file.txt").write_text("main", encoding="utf-8")
        git(repository, "add", "-A")
        git(repository, "commit", "-qm", "main")
        # 模拟 checkout@v4 fetch-depth: 0 已建立的 origin/main 远端跟踪引用。
        git(repository, "remote", "add", "origin", str(repository))
        git(repository, "update-ref", "refs/remotes/origin/main", "HEAD")
        output_file = self.root / "output"

        def run_tag_step(tag):
            if output_file.exists():
                output_file.unlink()
            return run(
                ["bash", str(script)],
                cwd=repository,
                env={"GITHUB_REF_NAME": tag, "GITHUB_OUTPUT": str(output_file)},
            )

        # 安全 tag 且提交位于 main 上：唯一允许发布的组合，并输出提交 SHA 与时间。
        allowed = run_tag_step(SAFE_TAG)
        self.assertEqual(0, allowed.returncode, allowed.stderr)
        head = git(repository, "rev-parse", "HEAD").stdout.strip()
        output_lines = output_file.read_text(encoding="utf-8").splitlines()
        self.assertEqual(f"commit={head}", output_lines[0])
        self.assertRegex(output_lines[1], r"^source_date_epoch=[0-9]+$")

        # 与脚本同一个字符集：任何越界输入都在构建之前失败。
        for tag in UNSAFE_TAGS:
            with self.subTest(tag=tag):
                rejected = run_tag_step(tag)
                self.assertNotEqual(0, rejected.returncode, f"tag {tag!r} must be rejected")

        # tag 提交不在 origin/main 历史上：同样不得发布。
        git(repository, "checkout", "-q", "-b", "side")
        (repository / "file.txt").write_text("side", encoding="utf-8")
        git(repository, "commit", "-qam", "side")
        outside = run_tag_step(SAFE_TAG)
        self.assertNotEqual(0, outside.returncode)
        self.assertIn("is not an ancestor of origin/main", outside.stderr)
        self.assertFalse(output_file.exists())


if __name__ == "__main__":
    unittest.main()
