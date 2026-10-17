# 内置文件变更的行为验证

修改 `fs.write` / `fs.edit` 时，验证顺序是输入拒绝、内容与编码、提交后的文件状态、并发与取消。
一条错误结果必须伴随“原文件没变”的证据，一条成功结果必须来自已提交内容。
契约见[内置工具设计](../modules/builtin-tools-design.md)，不要把展示 diff 当作提交成功的唯一依据。

## 执行入口

```bash
npm --prefix daemon ci
npm --prefix daemon run check
npm --prefix daemon run build
npm --prefix daemon run test -- test/coding/files.test.ts test/coding/text.test.ts
npm --prefix daemon run coverage
```

测试在真实本地文件系统创建、覆盖、改权限和原子替换临时文件，不操作部署数据、不调用模型。
POSIX 权限与特殊节点用例需对应宿主支持，报告中 skipped 不是通过。
代码覆盖率由 Vitest 与 `@vitest/coverage-v8` 统计（`npm --prefix daemon run coverage`）。

## 拒绝输入时文件是否仍然原样

[`test/coding/files.test.ts`](../../daemon/test/coding/files.test.ts)
直接检查结果与磁盘：

- 本地 `path` 必须是绝对路径，绝对 path 直接执行、相对 path 直接拒绝，不接受 workdir，也不能回退 cwd/HOME。
- schema 与能力校验拒绝缺参数。
- old_string 为空、无匹配、多处匹配未指定 replace_all 等场景被拒绝，不把匹配文本回显到错误。
- 相同文本及只改变归一化行尾拼写的 no-op 都拒绝，检查编码后的字节与原文件相同这一边界。
- 在内容 I/O 前拒绝目录、符号链接与非普通文件节点，避免设备/FIFO 带来的阻塞或副作用。
- 二进制判定需要读取内容，保证不会修改目标。

提交层 [`commit.ts`](../../daemon/src/coding/commit.ts)
在写入前检查目标属性，并在目标是目录、符号链接或非普通文件时拒绝提交。
read 的特殊文件证据见[Read 验证](builtin-read-tests.md)；
检索的二进制/遍历行为见[检索验证](builtin-search-tests.md)，不能用 write/edit 用例代替它们。

## 内容、BOM 与行尾

write 按调用方内容原样写入，不把换行改成目标既有风格；既有文件的 charset/BOM 保留。
覆盖既有文件时保留 BOM 与编码，创建/覆盖操作返回简单明确的成功提示。

edit 读取当前文件，不提供跨调用 stale-read 保护。
验证唯一匹配替换与 replace_all 替换全部匹配；保留 CRLF 与混合行尾；末尾换行可正常添加或删除。

[`test/coding/text.test.ts`](../../daemon/test/coding/text.test.ts)
验证 UTF-8 与 BOM 标记的 UTF-16LE/BE、NUL 判定、有效替换字符和严格无损编码。
输入编码按 UTF-8 或 BOM 识别，非法字节与孤立代理项直接拒绝。

## 原子提交、权限、并发和取消

[`commit.ts`](../../daemon/src/coding/commit.ts)
在目标目录内写临时文件（`.kk-mutation-*`）再原子替换。既有 POSIX 权限保留，新文件遵循平台创建权限与 umask，不提升权限。
目标不可读时因无法读取原内容而拒绝。

通过 64-stripe 细粒度分段锁串行化同一文件路径的并发修改，并发编辑不会相互覆盖；它不是跨进程锁或外部编辑器并发协议。
取消用例验证等待临界区期间取消不写入；已提交后到达的取消不把成功改成失败。

## Diff 是否忠实于提交内容

[`files.ts`](../../daemon/src/coding/files.ts) 中的 diff 渲染
验证结果文件的真实行号和内容：

- 单行、整文件、首行/末行修改与长编辑；
- 上下文展示与 hunk 间间隔折叠；
- 不按替换字符串伪造行号或虚报邻行删除；
- 大 diff 有界并报告截断，不拆开代理对；行号右对齐。

diff 是已提交结果的预览，不提供跨调用编辑基线。
核心实现位于 [`files.ts`](../../daemon/src/coding/files.ts)、[`commit.ts`](../../daemon/src/coding/commit.ts) 与 [`text.ts`](../../daemon/src/coding/text.ts)。
