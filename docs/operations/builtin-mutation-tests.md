# 内置文件变更的行为验证

修改 `fs.write` / `fs.edit` 时，验证顺序是输入拒绝、内容与编码、提交后的文件状态、并发与取消。
一条错误结果必须伴随“原文件没变”的证据，一条成功结果必须来自已提交内容。
契约见[内置工具设计](../modules/builtin-tools-design.md)，不要把展示 diff 当作提交成功的唯一依据。

## 执行入口

```bash
env JAVA_HOME="$JAVA_HOME_21" mvn -B -ntp -pl harness/daemon -am test \
  -Dtest='WriteEditMutationCapabilitiesTest,TextFileCodecTest,TextFileCommitTest,EditDiffRenderingTest,ReadWriteEditCapabilitiesTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

测试在真实本地文件系统创建、覆盖、改权限和原子替换临时文件，不操作部署数据、不调用模型。
POSIX 权限与特殊节点用例需对应宿主支持，报告中 skipped 不是通过。
只筛选部分测试时报告仅代表这个集合；完整 Daemon 回归可去掉 `-Dtest`。
Surefire 与 JaCoCo 分别在 `harness/daemon/target/surefire-reports`、`target/site/jacoco`，
Daemon 没有 `jacoco:check` 门禁。

## 拒绝输入时文件是否仍然原样

[`WriteEditMutationCapabilitiesTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WriteEditMutationCapabilitiesTest.java)
直接检查结果与磁盘：

- `writeAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt`、
  `editAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt`：绝对 path 不需 workdir，
  相对 path 必须显式提供绝对 workdir，不能回退 cwd/HOME。
- `writesToExplicitWorkdirAndRejectsMissingArguments`、`editSchemaRequiresEveryExecutedArgument`、
  `rejectsMissingNewString`、`rejectsMissingPath`：schema 与能力校验拒绝缺参数。
- `rejectsMissingOldStringWithoutEchoingIt`、`rejectsMultipleMatchesWithoutReplaceAll`、
  `rejectsOverlappingMatchesWithoutReplaceAll`、`rejectsOverlappingReplaceAll`、
  `rejectsEmptyOldString`：无匹配、歧义与重叠不能部分写入，也不把匹配文本回显到错误。
- `rejectsIdenticalOldAndNewString` 与 `rejectsNoOpEditDifferingOnlyByLineEndingSpelling`：
  相同文本及只改变归一化行尾拼写的 no-op 都拒绝，检查编码后的字节与原文件相同这一边界。
- `rejectsDirectoryAsWriteTarget`、`rejectsDirectoryEditTarget`、`rejectsNonRegularNodesBeforeIo`、
  `rejectsDanglingSymbolicLinkWriteTarget`：在内容 I/O 前拒绝不支持的节点，避免设备/FIFO 带来的阻塞或副作用。
  二进制判定需要读取内容，但 `rejectsExistingBinaryFileWithoutModifyingIt`、`rejectsBinaryEditTarget`
  保证不会修改目标。

提交层还有
[`TextFileCommitTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/TextFileCommitTest.java)
的 `rejectsSymbolicLinkTargetBeforeCommit`、`rejectsNonRegularTargetBeforeCommit`，
不依赖能力入口已经检查过目标。read 的特殊文件证据见[Read 验证](builtin-read-tests.md)；
检索的二进制/遍历行为见[检索验证](builtin-search-tests.md)，不能用 write/edit 用例代替它们。

## 内容、BOM 与行尾

write 按调用方内容原样写入，不把换行改成目标既有风格；既有文件的 charset/BOM 保留。
`writesCallerProvidedContentWithoutRewritingLineEndings`、
`preservesExistingUtf8BomWhileOverwriting`、`preservesExistingUtf16LeEncodingAndBomWhileOverwriting`
固定这一点，`reportsCreateAndOverwriteWithSimpleSuccessMessage` 固定创建/覆盖结果。

edit 读取当前文件，不提供跨调用 stale-read 保护（`editsCurrentFileContents`）。
`replacesExactTextAndReportsReplacements` 与 `supportsReplaceAllForMultipleMatches` 验证替换数；
`preservesCrlfLineEndings`、`preservesCrOnlyLineEndings`、`preservesMatchedMixedLineEndings`
验证未修改区域和匹配区域的换行继承。
无法判定的插入换行用 LF（`usesLfForAmbiguousInsertedNewlines`），末尾换行可添加或删除
（`canAddAndRemoveFinalNewline`），BOM 与 UTF-16LE 由 `preservesBomDuringEdit`、
`preservesUtf16LeEncodingDuringEdit` 验证。

[`TextFileCodecTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/TextFileCodecTest.java)
验证 UTF-8 与 BOM 标记的 UTF-16LE/BE、NUL 判定、有效替换字符和严格无损编码。
输入编码按 UTF-8 或 BOM 识别，非法字节直接拒绝。
无法编码的孤立代理项由 `rejectsLossyEncodingAndInvalidArguments` 及能力层的
`rejectsContentThatCannotBeRepresentedInExistingEncoding`、
`editRejectsContentThatCannotBeRepresentedInExistingEncoding` 验证提交前拒绝。

## 原子提交、权限、并发和取消

[`TextFileCommit`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextFileCommit.java)
在目标目录内写临时文件再原子替换。既有 POSIX 权限保留，新文件遵循平台创建权限与 umask，不提升权限。
`WriteEditMutationCapabilitiesTest.preservesExistingPosixPermissionsOfReplacedFile` 是权限保真的证据：
目录可写时 0444 目标仍可被原子覆盖并保留 0444
（`overwritesReadOnlyFileWhenDirectoryIsWritable`），0000 因原内容不可读而拒绝
（`rejectsWhollyUnreadableTargetWithoutModifyingIt`）。
只读目标不等于其目录禁止替换，不能以 root 运行的结果推断普通用户权限边界。

`serializesConcurrentEditsOfTheSameFile` 验证同一进程内同一文件的 read-modify-write 临界区，
并发编辑不会相互覆盖；它不是跨进程锁或外部编辑器并发协议。
取消用例验证等待临界区期间取消不写入；
`keepsCommittedWriteSuccessfulWhenCancelArrivesAfterCompletion` 验证已提交后到达的取消不把成功改成失败。
edit 在读取后、提交前还有一次取消检查；验证此时点时需要确定性屏障，
分别断言取消结果与目标文件未变。

不支持原子改名的平台直接抛 `AtomicMoveNotSupportedException`，**没有非原子覆盖回退**。
原生文件系统的成功路径不证明不支持原子改名的失败路径；
Windows、不同文件系统与权限能力应分别报告实际运行证据。

## Diff 是否忠实于提交内容

[`EditDiffRenderingTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/EditDiffRenderingTest.java)
验证结果文件的真实行号和内容：

- 单行、整文件、首行/末行修改与真实长编辑；
- 每侧 4 行上下文；前后与 hunk 间间隔在 4、8 行及刚超过边界时，省略号数量正确；
- 多个相邻或远离 hunk 合并/折叠，不额外保留无关文件尾部；
- `reportsRealResultingLinesInsteadOfRawReplacementText`、
  `deletingAcrossLineBoundaryDoesNotClaimNeighbourRemoved`：不按替换字符串伪造行号或虚报邻行删除；
- `hugeDiffIsTruncatedWithMarker`、`truncatedDiffDoesNotSplitEmojiSurrogatePairs`：
  大 diff 有界并报告截断，不拆开代理对；行号右对齐至少两位。

diff 是已提交结果的预览，不提供跨调用编辑基线。结合本次 JaCoCo 报告检查对齐与行号边界，
为实际未覆盖的失败路径补充能触发该路径的夹具。
核心度量类是 `WriteCapability`、`EditCapability`、`TextFileCommit`、`TextFileCodec`、`EnvironmentPaths`，
按仓库关键路径目标度量行覆盖率，分支作为参考；Daemon 不绑定自动 `jacoco:check` 门禁。
