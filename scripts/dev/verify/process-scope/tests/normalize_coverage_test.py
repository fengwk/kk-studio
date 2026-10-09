"""Acceptance and regression guards for the coverage input normalizer.

被终止的 helper JVM 会留下一条只写了一半的执行数据记录，官方 Reader 在读到这里会抛 ``EOFException``，整份文件因此无法参与合并。
``NormalizeCoverage.java`` 用官方 Reader 本身定位「最后一条完整记录」的字节边界，只裁掉这一段残缺尾部。这些测试同时锁住两件事：

* 合法输入必须逐字节保留并全部进入输出清单（空 helper、只有 header/session 的 helper、多记录文件都不许被丢）；
* 父 exec 与任何非「尾部执行数据 EOF」的损坏都必须 fail-closed，不能靠规范化掩盖。

工具环境（JDK 与固定版本的 ``org.jacoco.core``）缺失时这里**显式失败并给出准备方法**，不做无理由跳过，避免门禁悄悄失效。
测试夹具按已核实的 JaCoCo 记录格式直接构造字节，仅用于造出官方 Reader 能接受的输入，被测工具本身仍只用官方 Reader。

文件名刻意不匹配 ``test*.py``：它需要 ``org.jacoco.core``，只由 process-scope 覆盖率作业在 ``dependency:copy`` 取到 core 后点名执行，
通用无参数 discover（其它 workflow 里在 Maven 之前、且不保证有 JDK/core 的环境）不会误跑它。
"""

import os
import shutil
import struct
import subprocess
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
    raise RuntimeError("cannot locate the kk-studio worktree root; set KK_STUDIO_REPO_ROOT")


REPOSITORY_ROOT = repository_root()
NORMALIZE_SOURCE = (
    REPOSITORY_ROOT / "scripts" / "dev" / "verify" / "process-scope" / "NormalizeCoverage.java"
)

# 与 process-scope 工作流固定使用同一个 JaCoCo 版本，保证测试与 CI 读的是同一份官方 Reader。
JACOCO_CORE_VERSION = "0.8.11"
JACOCO_CORE_JAR_NAME = f"org.jacoco.core-{JACOCO_CORE_VERSION}.jar"


def java_executable():
    override = os.environ.get("KK_STUDIO_JAVA")
    if override:
        return override
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return shutil.which("java")


def jacoco_core_jar():
    """只按显式 ``JACOCO_CORE_JAR`` 或本地固定 m2 路径解析，不做宽泛扫描或版本猜测。"""
    override = os.environ.get("JACOCO_CORE_JAR")
    if override:
        path = Path(override)
        return path.resolve() if path.is_file() else None
    repository = Path(os.environ.get("MAVEN_REPO_LOCAL", Path.home() / ".m2" / "repository"))
    pinned = (
        repository
        / "org"
        / "jacoco"
        / "org.jacoco.core"
        / JACOCO_CORE_VERSION
        / JACOCO_CORE_JAR_NAME
    )
    return pinned.resolve() if pinned.is_file() else None


# --- JaCoCo exec 记录格式（与官方 ExecutionDataWriter/CompactDataOutput 一致，仅用于构造夹具）---
HEADER_MAGIC = 0xC0C0
FORMAT_VERSION = 0x1007
BLOCK_HEADER = 0x01
BLOCK_SESSIONINFO = 0x10
BLOCK_EXECUTIONDATA = 0x11


def exec_header():
    return (
        bytes([BLOCK_HEADER])
        + struct.pack(">H", HEADER_MAGIC)
        + struct.pack(">H", FORMAT_VERSION)
    )


def modified_utf(value):
    """按 DataOutputStream.writeUTF 语义编码 BMP 字符串（NUL 编码为 C0 80）。"""
    payload = bytearray()
    for character in value:
        code = ord(character)
        if code == 0:
            payload += b"\xc0\x80"
        elif code < 0x80:
            payload.append(code)
        elif code < 0x800:
            payload += bytes([0xC0 | (code >> 6), 0x80 | (code & 0x3F)])
        else:
            payload += bytes(
                [0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F)]
            )
    return struct.pack(">H", len(payload)) + bytes(payload)


def var_int(value):
    encoded = bytearray()
    while True:
        if value & ~0x7F == 0:
            encoded.append(value)
            return bytes(encoded)
        encoded.append(0x80 | (value & 0x7F))
        value >>= 7


def boolean_array(values):
    encoded = bytearray(var_int(len(values)))
    buffer = 0
    size = 0
    for value in values:
        if value:
            buffer |= 1 << size
        size += 1
        if size == 8:
            encoded.append(buffer)
            buffer = 0
            size = 0
    if size:
        encoded.append(buffer)
    return bytes(encoded)


def session_block(session_id="scope\r\n\ufffdNUL\u0000x", start=1, dump=2):
    return (
        bytes([BLOCK_SESSIONINFO])
        + modified_utf(session_id)
        + struct.pack(">q", start)
        + struct.pack(">q", dump)
    )


def execution_block(class_id, class_name, probes):
    return (
        bytes([BLOCK_EXECUTIONDATA])
        + struct.pack(">q", class_id)
        + modified_utf(class_name)
        + boolean_array(probes)
    )


def valid_exec(records=3, session_id="scope\r\n中\u0000x"):
    blocks = [
        execution_block(index, f"a/B{index}", [True, False, True, True, False])
        for index in range(records)
    ]
    return exec_header() + session_block(session_id) + b"".join(blocks)


class NormalizeCoverageTest(unittest.TestCase):
    """规范化只裁掉被终止 helper 的最后一条未完成记录，其余输入必须完整保留或严格失败。"""

    @classmethod
    def setUpClass(cls):
        cls.java = java_executable()
        cls.core_jar = jacoco_core_jar()
        missing = []
        if cls.java is None:
            missing.append("a JDK (set JAVA_HOME or put java on PATH)")
        if cls.core_jar is None:
            missing.append(
                "org.jacoco.core %s (set JACOCO_CORE_JAR, or prepare it once with `mvn -B -ntp "
                "dependency:copy -Dartifact=org.jacoco:org.jacoco.core:%s:jar "
                "-DoutputDirectory=<dir>`)" % (JACOCO_CORE_VERSION, JACOCO_CORE_VERSION)
            )
        if missing:
            raise RuntimeError(
                "the coverage normalization tool cannot run in this environment; missing "
                + " and ".join(missing)
            )

    def run_normalize(self, root, files, output=None, prepare=None):
        inputs = root / "inputs"
        for name, content in files.items():
            path = inputs / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        manifest = root / "exec-files.txt"
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(
            "\n".join(str((inputs / name).resolve()) for name in files), encoding="utf-8"
        )
        target = output if output is not None else root / "normalized"
        if prepare is not None:
            Path(target).mkdir(parents=True, exist_ok=True)
            prepare(Path(target))
        result = subprocess.run(
            [
                self.java,
                "-cp",
                str(self.core_jar),
                str(NORMALIZE_SOURCE),
                str(manifest),
                str(target),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        return result, inputs, Path(target)

    @staticmethod
    def normalized_paths(output):
        return Path(output / "exec-files.txt").read_text(encoding="utf-8").split("\n")

    @staticmethod
    def transcript(result):
        """失败诊断走 stderr，成功摘要走 stdout：断言规则时看完整输出。"""
        return result.stdout + result.stderr

    def test_complete_dumps_are_copied_byte_identically_with_every_record(self):
        """合法完整文件（含多记录与 CR/LF/NUL session）必须逐字节保留，并全部进入输出清单。"""
        helper = valid_exec(records=3)
        parent = valid_exec(records=2, session_id="parent")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result, inputs, output = self.run_normalize(
                root, {"jacoco-helper/helper.exec": helper, "jacoco.exec": parent}
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("normalized 2 exec files: 2 complete, 0 recovered", result.stdout)
            normalized = self.normalized_paths(output)
            self.assertEqual(2, len(normalized))
            self.assertEqual(helper, (inputs / "jacoco-helper/helper.exec").read_bytes())
            self.assertEqual(helper, Path(normalized[0]).read_bytes())
            self.assertEqual(parent, Path(normalized[1]).read_bytes())

    def test_helper_dumps_without_any_record_are_accepted_unchanged(self):
        """被终止的 helper 可能只留下空文件或仅 header/session：它们是合法（零/稀疏）dump，不能误判成损坏。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            files = {
                "jacoco-helper/empty.exec": b"",
                "jacoco-helper/session-only.exec": exec_header() + session_block(),
            }
            result, inputs, output = self.run_normalize(root, files)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("2 complete, 0 recovered", result.stdout)
            normalized = self.normalized_paths(output)
            for name, target in zip(files, normalized):
                self.assertEqual((inputs / name).read_bytes(), Path(target).read_bytes())
            # 空 dump 保持 0 字节，绝不回填任何探针占位。
            self.assertEqual(b"", Path(normalized[0]).read_bytes())
            self.assertTrue(Path(normalized[1]).is_file())
            self.assertGreater(Path(normalized[1]).stat().st_size, 0)

    def test_truncated_helper_tail_keeps_every_complete_record(self):
        """helper 尾部在 classId / name / probes 中截断时，只丢那一条未完成记录，前面的记录逐字节保留。"""
        complete = valid_exec(records=3)
        tail = execution_block(99, "a/Tail", [True] * 9)
        truncated = complete + tail
        cuts = {
            "class-id": len(complete) + 1 + 3,
            "class-name": len(complete) + 1 + 8 + 2,
            "probes": len(truncated) - 1,
        }
        for label, cut in cuts.items():
            with self.subTest(label=label):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    result, _, output = self.run_normalize(
                        root, {"jacoco-helper/helper.exec": truncated[:cut]}
                    )
                    self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                    self.assertIn("1 recovered", result.stdout)
                    self.assertIn(
                        "kept=%d dropped=%d" % (len(complete), cut - len(complete)),
                        result.stdout,
                    )
                    recovered = Path(self.normalized_paths(output)[0]).read_bytes()
                    self.assertEqual(complete, recovered)
                    # 恢复后的文件本身必须能被官方 Reader 完整读取：再规范化一次不产生任何变化。
                    second = root / "recovered"
                    second.mkdir()
                    (second / "helper.exec").write_bytes(recovered)
                    rerun_result, _, rerun_output = self.run_normalize(
                        root / "again", {"jacoco-helper/helper.exec": recovered}, output=second
                    )
                    self.assertEqual(
                        0, rerun_result.returncode, rerun_result.stdout + rerun_result.stderr
                    )
                    self.assertIn("1 complete, 0 recovered", rerun_result.stdout)

    def test_truncated_parent_exec_is_rejected(self):
        """父 exec 没有「被终止 helper」这一豁免：任何 EOF 都必须失败，绝不能恢复。"""
        truncated = valid_exec(records=2) + execution_block(9, "a/Tail", [True, False])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result, _, output = self.run_normalize(root, {"jacoco.exec": truncated[:-1]})
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("truncated execution record", self.transcript(result))
            self.assertIn("offset", self.transcript(result))
            self.assertFalse((output / "exec-files.txt").exists())

    def test_helper_tail_without_a_complete_predecessor_is_rejected(self):
        """尾部 EOF 之前必须已有完整 header/session 与至少一条完整记录，否则只能失败。"""
        partial = exec_header() + session_block() + execution_block(1, "a/B", [True])[:5]
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result, _, _ = self.run_normalize(root, {"jacoco-helper/helper.exec": partial})
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn(
                "truncated execution record without a complete predecessor", self.transcript(result)
            )

    def test_truncated_header_or_session_is_rejected_even_for_a_helper(self):
        """截断发生在 header/session 时不是那条真实残缺尾部，helper 也不许恢复。"""
        cases = {
            "truncated header": (exec_header()[:3], "truncated header"),
            "truncated session record": (
                exec_header() + session_block()[:4],
                "truncated session record",
            ),
        }
        for label, (content, rule) in cases.items():
            with self.subTest(label=label):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    result, _, _ = self.run_normalize(root, {"jacoco-helper/helper.exec": content})
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn(rule, self.transcript(result))

    def test_corrupt_and_unknown_inputs_fail_closed(self):
        """坏 magic/版本、未知 block、非 header 开头、完整 payload 损坏都必须失败，不能靠规范化掩盖。"""
        valid_prefix = exec_header() + session_block() + execution_block(1, "a/B", [True, False])
        corrupt_name = (
            bytes([BLOCK_EXECUTIONDATA])
            + struct.pack(">q", 1)
            + struct.pack(">H", 2)
            + b"\xc0\x00"
        )
        cases = {
            "unknown block": (
                exec_header() + session_block() + b"\x77",
                "not an execution data file",
            ),
            "bad magic": (
                bytes([BLOCK_HEADER])
                + struct.pack(">H", 0x1234)
                + struct.pack(">H", FORMAT_VERSION),
                "invalid header",
            ),
            "bad version": (
                bytes([BLOCK_HEADER])
                + struct.pack(">H", HEADER_MAGIC)
                + struct.pack(">H", FORMAT_VERSION + 1),
                "incompatible execution data version",
            ),
            "no header": (
                session_block() + execution_block(1, "a/B", [True]),
                "not an execution data file",
            ),
            "corrupt payload": (valid_prefix + corrupt_name, "corrupt execution record"),
        }
        for label, (content, rule) in cases.items():
            with self.subTest(label=label):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    result, _, _ = self.run_normalize(root, {"jacoco-helper/helper.exec": content})
                    self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                    self.assertIn(rule, self.transcript(result))

    def test_output_never_overwrites_an_input_or_its_manifest(self):
        """输出目录落在输入旁边时，输出文件与输出清单都不能盖掉任何输入。"""
        original = valid_exec(records=2)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            # 输入正好叫 0000.exec，输出目录就是它所在目录：工具必须在写之前失败。
            result, inputs, _ = self.run_normalize(
                root, {"0000.exec": original}, output=root / "inputs"
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("would overwrite an input", self.transcript(result))
            self.assertEqual(original, (inputs / "0000.exec").read_bytes())
            # 输入正好叫 exec-files.txt：输出清单同样不许覆盖它。
            result, inputs, _ = self.run_normalize(
                root / "manifest-case", {"exec-files.txt": original}, output=root / "manifest-case" / "inputs"
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("would overwrite an input", self.transcript(result))
            self.assertEqual(
                original, (inputs / "exec-files.txt").read_bytes()
            )

    def test_output_directory_holding_the_source_manifest_is_rejected(self):
        """输出目录就是源清单的父目录时，同名输出清单会覆盖源清单，必须在写任何东西之前失败。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result, inputs, _ = self.run_normalize(
                root, {"helper.exec": valid_exec(records=1)}, output=root
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("would overwrite an input", self.transcript(result))
            self.assertFalse((root / "0000.exec").exists())
            # 源清单逐字节保留，仍然指向原输入。
            self.assertEqual(
                str((inputs / "helper.exec").resolve()),
                (root / "exec-files.txt").read_text(encoding="utf-8").strip(),
            )

    def test_symlink_output_aliasing_an_input_is_rejected(self):
        """输出路径是符号链接（哪怕指向输入文件）时不许跟随写入，否则会把输入改掉。"""
        original = valid_exec(records=2)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            victim = root / "inputs" / "helper.exec"

            def prepare(output):
                (output / "0000.exec").symlink_to(victim)

            result, _, _ = self.run_normalize(
                root, {"helper.exec": original}, output=root / "normalized", prepare=prepare
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("is a symbolic link", self.transcript(result))
            self.assertEqual(original, victim.read_bytes())

    def test_hardlink_output_aliasing_an_input_is_rejected(self):
        """输出路径与输入是同一 inode 的硬链接时，Files.isSameFile 必须把它判成碰撞。"""
        original = valid_exec(records=2)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            victim = root / "inputs" / "helper.exec"

            def prepare(output):
                try:
                    os.link(victim, output / "0001.exec")
                except OSError as error:  # pragma: no cover - filesystem dependent
                    raise unittest.SkipTest(f"hardlinks are unavailable here: {error}") from error

            result, _, _ = self.run_normalize(
                root,
                {"a.exec": valid_exec(records=1), "helper.exec": original},
                output=root / "normalized",
                prepare=prepare,
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("would overwrite an input", self.transcript(result))
            self.assertEqual(original, victim.read_bytes())

    def test_colliding_later_numbered_output_is_rejected_before_any_write(self):
        """碰撞发生在较后的编号上时，预检也必须先跑完，早先的编号不能被写出来。"""
        later = valid_exec(records=2)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result, inputs, _ = self.run_normalize(
                root,
                {"a.exec": valid_exec(records=1), "0001.exec": later},
                output=root / "inputs",
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("would overwrite an input", self.transcript(result))
            self.assertFalse((inputs / "0000.exec").exists())
            self.assertEqual(later, (inputs / "0001.exec").read_bytes())

    def test_every_input_produces_exactly_one_unique_output(self):
        """清单条目数必须与输出文件数一一对应：不许漏文件、不许一个文件被静默丢出合并。"""
        files = {f"jacoco-helper/helper-{index}.exec": valid_exec(records=index + 1) for index in range(5)}
        files["jacoco.exec"] = valid_exec(records=2, session_id="parent")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result, _, output = self.run_normalize(root, files)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            normalized = self.normalized_paths(output)
            self.assertEqual(len(files), len(normalized))
            self.assertEqual(len(files), len(set(normalized)))
            for target in normalized:
                self.assertTrue(Path(target).is_file())


if __name__ == "__main__":
    unittest.main()
