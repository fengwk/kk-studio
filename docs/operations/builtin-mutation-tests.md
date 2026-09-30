# 内置文件变更能力测试映射

本文面向维护 `fs.write`、`fs.edit` 与文本编解码、原子提交的开发者：说明这些能力当前生效的行为契约、
`pi-base` 用例落到了哪条测试、哪些用例被改写或未迁移，以及覆盖率报告的位置与仍然未覆盖的守卫分支。
契约本身见[内置工具设计](../modules/builtin-tools-design.md)，跨模块边界见[系统设计](../system-design.md)。

## 契约与证据

| 契约点 | 语义 | 主要证据 |
| --- | --- | --- |
| write 内容 | 按调用方给定的内容原样落盘，不把行尾改写为目标文件既有风格；既有文件的 charset 与 BOM 仍然保留 | [`WriteEditMutationCapabilitiesTest#writesCallerProvidedContentWithoutRewritingLineEndings`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |
| workdir 可选 | 绝对 `path` 无需 `workdir`；相对 `path` 缺少显式 workdir 在执行前拒绝，绝不回退到 cwd/HOME 或任何默认目录 | [`WriteEditMutationCapabilitiesTest#writeAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |
| write/edit 编码 | 复用既有文件检测出的 charset 与 BOM，无法无损编码时在写入前失败 | [`TextFileCodecTest#rejectsLossyEncodingAndInvalidArguments`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/TextFileCodecTest.java) |
| edit 替换 | `old_string` 归一化匹配、`replace_all` 与重叠判定、替换结果只改动匹配区域 | [`WriteEditMutationCapabilitiesTest#replacesExactTextAndReportsReplacements`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |
| edit 行尾 | 未修改区域保留原有行尾；替换带来的换行沿用被匹配文本的行尾，混合文件里无法判定时用 LF | [`WriteEditMutationCapabilitiesTest#preservesMatchedMixedLineEndings`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |
| edit diff | diff 行号与内容来自结果文件的真实行；上下文按每侧 4 行折叠，行号右对齐至少 2 位；超限截断不拆开代理对 | [`EditDiffRenderingTest#truncatedDiffDoesNotSplitEmojiSurrogatePairs`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/EditDiffRenderingTest.java) |
| 原子提交 | 既有文件的替换保留原 POSIX 权限；新文件遵循平台默认创建权限（umask），不提升权限 | [`WriteEditMutationCapabilitiesTest#preservesExistingPosixPermissionsOfReplacedFile`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |
| 只读覆盖 | 目录可写时 0444 只读目标可被合法原子覆盖并保留 0444；目标 0000 因原内容不可读而拒绝 | [`WriteEditMutationCapabilitiesTest#overwritesReadOnlyFileWhenDirectoryIsWritable`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |
| 文件类型 | 目录、设备、FIFO、悬空符号链接与二进制文件在任何 I/O 之前拒绝 | [`TextFileCommitTest#rejectsNonRegularTargetBeforeCommit`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/TextFileCommitTest.java) |
| 并发与取消 | 同一文件的 read-modify-write 串行执行；提交前取消不落盘，提交后取消不改变成功结论 | [`WriteEditMutationCapabilitiesTest#serializesConcurrentEditsOfTheSameFile`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java) |

## 执行方式

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -B -ntp -pl harness/daemon -am test
```

单类运行用 `-Dtest=WriteEditMutationCapabilitiesTest` 等；报告在 `harness/daemon/target/surefire-reports`，
覆盖率报告在 `harness/daemon/target/site/jacoco`。

## 用例映射

「处理」列取值：迁移（同语义落到 kk 用例）、改写（语义保留但断言或机制因平台不同而调整）、不迁移（在 kk 无对应契约）。

### write-behavior

| pi-base 用例 | 对应证据 | 处理 |
| --- | --- | --- |
| renders full multi-line write call previews with explicit workdir | — | 不迁移：工具调用预览渲染属于模型侧展示层，kk 的 write 只返回结果文本 |
| requires path and defaults workdir during execution | `writesToExplicitWorkdirAndRejectsMissingArguments`、`writeAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt` | 改写：kk 的 workdir 对 write 可选（绝对 path 无需它），相对 path 缺少显式 workdir 即拒绝，不回退到 cwd/HOME |
| writes caller-provided line endings when overwriting an existing file | `writesCallerProvidedContentWithoutRewritingLineEndings` | 迁移 |
| rejects an existing binary file without modifying it | `rejectsExistingBinaryFileWithoutModifyingIt` | 迁移 |
| calls onSuccessfulWrite hook and reports overwrites | `reportsCreateAndOverwriteWithSimpleSuccessMessage` | 改写：kk 没有成功回调，用 “Created/Overwrote … successfully.” 结果文本观测 |
| reports success once the file write has committed even if cancellation arrives at completion | `keepsCommittedWriteSuccessfulWhenCancelArrivesAfterCompletion` | 迁移 |
| keeps a committed write successful when its observer throws | — | 不迁移：kk 提交后没有可抛错的用户回调 |
| formatWriteCall collapsed preview（7 行上限、单复数提示、尾换行、缺失 path 等） | — | 不迁移：同预览渲染层 |
| — | `overwritesReadOnlyFileWhenDirectoryIsWritable`、`rejectsWhollyUnreadableTargetWithoutModifyingIt` | 新增：0444 只读目标在目录可写时可合法原子覆盖并保留权限；0000 目标因原内容不可读而拒绝 |

### edit-write-index

| pi-base 用例 | 对应证据 | 处理 |
| --- | --- | --- |
| adds retry guidance to edit argument validation failures | `editSchemaRequiresEveryExecutedArgument`、`editAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt` | 改写：参数校验由共享 schema 承担，这里核对必填集（workdir 可选，不在必填集内）；重跑提示文案不在契约内 |
| write returns a simple success message | `reportsCreateAndOverwriteWithSimpleSuccessMessage` | 迁移 |
| write preserves an existing BOM while overwriting content | `preservesExistingUtf8BomWhileOverwriting` | 迁移 |
| write preserves existing utf-16le encoding and BOM while overwriting content | `preservesExistingUtf16LeEncodingAndBomWhileOverwriting` | 迁移 |
| write rejects missing content when schema validation is bypassed | `writesToExplicitWorkdirAndRejectsMissingArguments` | 迁移（缺失 `content` 分支） |
| write rejects text that cannot be represented in the existing legacy encoding | `rejectsContentThatCannotBeRepresentedInExistingEncoding` | 改写：kk 不支持 legacy 编码，改用 UTF-16LE 目标不可无损编码（孤立代理）验证同一失败语义 |
| edits a file using old_string/new_string | `replacesExactTextAndReportsReplacements` | 迁移 |
| rejects edit when old_string is not found | `rejectsMissingOldStringWithoutEchoingIt` | 迁移（并核对不回显匹配文本） |
| rejects edit when old_string matches multiple times | `rejectsMultipleMatchesWithoutReplaceAll` | 迁移 |
| rejects edit when old_string has overlapping matches | `rejectsOverlappingMatchesWithoutReplaceAll` | 迁移 |
| supports replace_all for multiple matches | `supportsReplaceAllForMultipleMatches` | 迁移 |
| rejects replace_all when matches overlap | `rejectsOverlappingReplaceAll` | 迁移 |
| rejects identical old_string and new_string | `rejectsIdenticalOldAndNewString` | 迁移 |
| rejects no-op edits that differ only by line-ending spelling | `rejectsNoOpEditDifferingOnlyByLineEndingSpelling` | 迁移 |
| rejects empty old_string | `rejectsEmptyOldString` | 迁移 |
| rejects missing new_string when schema validation is bypassed | `rejectsMissingNewString` | 迁移 |
| edits the current file contents without cross-call stale-read protection | `editsCurrentFileContents` | 迁移 |
| preserves CRLF line endings during edit | `preservesCrlfLineEndings` | 迁移 |
| preserves CR line endings during multiline edit | `preservesCrOnlyLineEndings` | 迁移 |
| preserves matched mixed line endings for replaced newlines | `preservesMatchedMixedLineEndings` | 迁移 |
| uses LF for ambiguous inserted newlines in mixed-ending files | `usesLfForAmbiguousInsertedNewlines` | 迁移 |
| can add and remove a final newline through the normalized LF view | `canAddAndRemoveFinalNewline` | 迁移 |
| preserves BOM during edit | `preservesBomDuringEdit` | 迁移 |
| preserves utf-16le encoding during edit | `preservesUtf16LeEncodingDuringEdit` | 迁移 |
| edit rejects text that cannot be represented in the existing legacy encoding | `editRejectsContentThatCannotBeRepresentedInExistingEncoding` | 改写：同 write 的编码边界 |
| edit reports missing path | `rejectsMissingPath` | 迁移 |

### special-file-tools

| pi-base 用例 | 对应证据 | 处理 |
| --- | --- | --- |
| rejects non-regular nodes before read, grep, edit, or write performs file I/O | `rejectsNonRegularNodesBeforeIo`、`rejectsDirectoryAsWriteTarget`、`rejectsDirectoryEditTarget`、`rejectsBinaryEditTarget`、`rejectsDanglingSymbolicLinkWriteTarget`、`TextFileCommitTest#rejectsSymbolicLinkTargetBeforeCommit` | 迁移并强化：新增悬空符号链接与直接对提交层断言的用例；read/grep 侧沿用既有 FindGrepCapabilitiesTest |

### text-codec

| pi-base 用例 | 对应证据 | 处理 |
| --- | --- | --- |
| distinguishes true binary data from valid replacement-character text | `distinguishesReplacementCharacterTextFromBinaryBytes` | 迁移 |
| treats common whitespace as text while still detecting dense controls | `treatsWhitespaceAndEscapeBytesAsText` | 改写：kk 不采用控制字符密度启发式，可疑字节按 UTF-8 严格解码判定 |
| detects BOMs and UTF-16 without a BOM | `decodesEverySupportedBomFormWithItsExactEncoding` | 改写：只覆盖 BOM 形式；无 BOM 的 UTF-16 启发式不在契约内，避免与 read/grep 的文本判定不一致 |
| decodes legacy encoded text instead of classifying it as binary | — | 不迁移：无 legacy 编码猜测 |
| rejects NUL-bearing binary samples after BOM and UTF-16 checks | `rejectsNulBearingBinarySamples` | 迁移 |
| falls back to UTF-8 for suspicious decoded text that is not binary bytes | `treatsWhitespaceAndEscapeBytesAsText` | 改写：kk 的“可疑文本”回退就是严格 UTF-8 解码成功即视为文本，没有独立分支 |
| maps encodings to BOM kinds and validates lossy writes | `rejectsLossyEncodingAndInvalidArguments` | 迁移 |

### edit-diff

| pi-base 用例 | 对应证据 | 处理 |
| --- | --- | --- |
| trailing context / keeps the first contextLines lines closest to the preceding hunk | `trailingContextKeepsOnlyLinesClosestToHunk` | 迁移 |
| emits no '...' for trailing context of exactly contextLines lines | `trailingContextOfExactlyContextLinesHasNoElision` | 迁移 |
| emits a single '...' for trailing context of contextLines + 1 lines | `trailingContextOfContextLinesPlusOneElidesOnce` | 迁移 |
| does not preserve the trailing tail (matches git, not head+...+tail) | `trailingContextDoesNotPreserveFileTail` | 迁移 |
| leading context / keeps the last contextLines lines closest to the following hunk | `leadingContextKeepsOnlyLinesClosestToHunk` | 迁移 |
| emits no '...' for leading context of exactly contextLines lines | `leadingContextOfExactlyContextLinesHasNoElision` | 迁移 |
| emits a single '...' for leading context of contextLines + 1 lines | `leadingContextOfContextLinesPlusOneElidesOnce` | 迁移 |
| keeps both ends when the gap is short enough to fit two context windows back-to-back | `shortInterHunkGapRendersWithoutElision` | 迁移 |
| inserts exactly one '...' when two hunks are separated by more than 2*contextLines | `distantHunksElideExactlyOnce` | 迁移 |
| merges two hunks without a '...' when their gap fits within 2*contextLines | `mergedHunksWithinDoubleContextRenderWithoutElision` | 迁移 |
| emits no '...' for an inter-hunk block of exactly 2*contextLines lines | `interHunkBlockOfExactlyDoubleContextHasNoElision` | 迁移 |
| emits a single '...' for an inter-hunk block of 2*contextLines + 1 lines | `interHunkBlockJustOverDoubleContextElidesExactlyOnce` | 迁移 |
| emits exactly two '...' for three hunks with mixed gap sizes | `threeHunksWithMixedGapsElideTwice` | 迁移 |
| renders three adjacent hunks (no context between them) without any fold | `adjacentHunksRenderWithoutElision` | 迁移 |
| handles a file with no changes by emitting an empty diff | `identicalReplacementIsRejectedWithoutDiff` | 改写：kk 在无变化时直接拒绝并返回错误，不产出空 diff |
| handles a single-line change with no surrounding context | `singleLineChangeRendersExactHunk` | 迁移（行号宽度按 kk 的 2 位对齐：`- 1|old`） |
| handles a whole-file replacement without invoking appendContextBlock | `wholeFileReplacementRendersEveryLine` | 迁移 |
| renders a hunk at the very first line with long trailing context | `hunkAtFirstLineWithLongTrailingContext` | 迁移 |
| renders a hunk at the very last line with long leading context | `hunkAtLastLineWithLongLeadingContext` | 迁移 |
| real-world regression / renders a long-edit 3-line block + 10-line trailing context as a tight fold | `realWorldLongEditRendersTightFold` | 迁移 |
| — | `reportsRealResultingLinesInsteadOfRawReplacementText`、`deletingAcrossLineBoundaryDoesNotClaimNeighbourRemoved`、`hugeDiffIsTruncatedWithMarker`、`truncatedDiffDoesNotSplitEmojiSurrogatePairs` | 新增：针对行号错位、虚报删除、超大变更区域截断与截断拆开代理对的缺陷回归 |

### edit-queue 与 line-endings

| pi-base 用例 | 对应证据 | 处理 |
| --- | --- | --- |
| edit-queue / serializes same-file concurrent edits through one read-modify-write critical section | `serializesConcurrentEditsOfTheSameFile` | 迁移 |
| line-endings / classifies and labels each concrete ending style plus mixed content | `writesCallerProvidedContentWithoutRewritingLineEndings`、`preservesMatchedMixedLineEndings` | 改写：行尾分类是内部模型，行为级覆盖而非 API 级断言 |
| line-endings / normalizes all endings to LF and serializes a normalized document | `usesLfForAmbiguousInsertedNewlines` | 改写：同上 |
| line-endings / rejects inconsistent documents during serialization | `rejectsNoOpEditDifferingOnlyByLineEndingSpelling` | 改写：非法行尾文档不可由文件构造，只验证与之等价的 no-op 拒绝 |
| line-endings / parses and serializes mixed line endings without losing structure | `preservesMatchedMixedLineEndings` | 改写：同上 |
| line-endings / defaults to LF when a file has no separators | `usesLfForAmbiguousInsertedNewlines` | 改写：同上 |
| line-endings / preserves an empty trailing line when the file ends with a newline | `canAddAndRemoveFinalNewline` | 改写：同上 |

## 覆盖率的位置与口径

以 `env JAVA_HOME=$JAVA_HOME_21 mvn -B -ntp -pl harness/daemon -am test` 执行整个 daemon 模块，覆盖率报告写在
`harness/daemon/target/site/jacoco`。写入与编辑路径的相关类是 `WriteCapability`、`EditCapability`、`TextFileCommit`、
`TextFileCodec`、`EnvironmentPaths`；逐类百分比随实现漂移，本文不固定数字，只说明口径：
`harness/daemon` 不绑定 JaCoCo `check`，因此覆盖率是参考基线而不是构建门禁；未覆盖行见下节。

## 未覆盖的守卫分支

| 位置 | 未覆盖原因 | 风险 |
| --- | --- | --- |
| `EditCapability` 的编码后字节等于原文件的兜底拒绝、空对齐区与结果行定位越界抛错 | 构造性不可达：归一化后文本不同即必然改变字节；对齐算法的调用点已保证索引落在文本文档内 | 低：保留为提交前与算法内部的防御式断言，触发即表示上游逻辑被改坏 |
| `EditCapability` 提交前取消检查中“读取之后、提交之前”的一次 | 该时点只在 worker 已越过读取时才可命中，测试只能确定性命中读取前的检查 | 低：提交前两次检查语义相同，取消仍不会覆盖已提交结果 |

平台边界说明：原子替换不再有 `AtomicMoveNotSupportedException` 回退，平台不支持原子改名时直接作为提交前失败抛出，
该分支已无额外代码（原先的 catch 分支随回退一起消失）。自动化只在开发机原生文件系统上验证：本机默认文件系统与 JDK 自带
zip 文件系统都支持原子改名，因此**没有**确定性用例能触发“平台不支持原子改名”。未在 Windows 上运行过这些用例，也不声称
POSIX 缺失或原子改名不可用的平台行为已被测试覆盖；实现与 javadoc 只如实声明该平台限制。

## 与共享用例的边界

`ReadWriteEditCapabilitiesTest` 是 read/write/edit 共享的用例集合。它对 write 只断言现行契约（按调用内容原样写入），
不保留旧的行尾保留断言；该集合其余部分继续由共享用例承担，write/edit 的差异语义（含 edit 侧的行尾继承）由本文所列
用例独立保证。
