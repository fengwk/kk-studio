#!/usr/bin/env python3
"""收集三个平台上传的覆盖率输入，并确认它们可以被合并。

执行范围的关键逻辑同时存在于「父进程」与「helper」两个 JVM，且平台专属分支只会在对应平台上被执行（Windows 的 Job 语义、
macOS 的非 Linux 分支）。要得到有意义的数字就必须合并三平台数据；而 JaCoCo 按 class id（类字节的哈希）归并 session，
所以三个平台的 class 文件必须逐字节一致，否则合并会静默丢数据。这里把这件事变成显式失败。
"""

import hashlib
import sys
from pathlib import Path


def class_digest(classes_dir: Path) -> str:
    digest = hashlib.sha256()
    files = sorted(path for path in classes_dir.rglob("*.class"))
    if not files:
        raise SystemExit(f"no class file under {classes_dir}")
    for path in files:
        digest.update(str(path.relative_to(classes_dir)).encode("utf-8"))
        digest.update(path.read_bytes())
    return digest.hexdigest()


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: collect-coverage-inputs.py <downloaded-artifacts-dir> <output-dir>", file=sys.stderr)
        return 2
    artifacts = Path(sys.argv[1])
    output = Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)

    platforms = sorted(path for path in artifacts.iterdir() if path.is_dir())
    if len(platforms) != 3:
        print(f"FAIL expected three platform artifacts, found {[p.name for p in platforms]}")
        return 1

    exec_files = []
    digests = {}
    classes_source = None
    for platform in platforms:
        parent_exec = platform / "jacoco.exec"
        helper_dir = platform / "jacoco-helper"
        classes_dir = platform / "classes"
        if not parent_exec.is_file():
            print(f"FAIL {platform.name} has no jacoco.exec")
            return 1
        helpers = sorted(helper_dir.glob("*.exec")) if helper_dir.is_dir() else []
        if not helpers:
            print(f"FAIL {platform.name} has no helper exec data")
            return 1
        if not classes_dir.is_dir():
            print(f"FAIL {platform.name} has no class files")
            return 1
        digests[platform.name] = class_digest(classes_dir)
        classes_source = classes_source or classes_dir
        exec_files.append(parent_exec)
        exec_files.extend(helpers)
        print("%-16s parent=1 helper=%d" % (platform.name, len(helpers)))

    if len(set(digests.values())) != 1:
        print("FAIL class files differ across platforms, merged coverage would silently drop sessions")
        for name, digest in digests.items():
            print("  %s %s" % (name, digest))
        return 1
    print("class digest %s identical on every platform" % list(digests.values())[0])

    # 类别目录按平台各自上传，这里固定取第一份：三份已经证明逐字节一致。链接必须用绝对路径——相对路径会相对**链接自身所在
    # 目录**解析，调用方换个工作目录就变成断链，报告步骤会以「找不到 class 文件」失败。
    classes_link = output / "classes"
    if classes_link.is_symlink() or classes_link.exists():
        classes_link.unlink()
    classes_link.symlink_to(classes_source.resolve(), target_is_directory=True)
    if not classes_link.is_dir():
        print(f"FAIL {classes_link} does not resolve to a class directory")
        return 1
    (output / "exec-files.txt").write_text("\n".join(str(path) for path in exec_files), encoding="utf-8")
    print("collected %d exec files" % len(exec_files))
    return 0


if __name__ == "__main__":
    sys.exit(main())
