# 内置文件变更的行为验证

修改 `fs.write` / `fs.edit` 时，验证顺序是输入拒绝、内容与编码、提交后的文件状态、并发与取消。
一条错误结果必须伴随“原文件没变”的证据，一条成功结果必须来自已提交内容。
契约见[内置工具设计](../modules/builtin-tools-design.md)，不要把展示 diff 当作提交成功的唯一依据。

## 执行入口

```bash
# Daemon 单元与集成测试（含编辑审查与预期文件校验）
npm --prefix daemon ci
npm --prefix daemon run check
npm --prefix daemon run build
npm --prefix daemon run test -- test/coding/files.test.ts test/coding/text.test.ts test/coding/edit-review.test.ts
npm --prefix daemon run coverage

# 前端文件变更与审查卡片浏览器回归
npm --prefix frontend run build:layout
(
  cd frontend
  npx playwright test --config playwright.layout.config.ts browser-tests/mutation-review.pw.ts
)
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

edit 读取当前文件；需要人工审批时，执行必须匹配审查时冻结的路径与原始字节哈希，自动放行则直接基于执行时文件计算变更。
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

## 权威审查、预期文件校验与真实 Diff

[`files.ts`](../../daemon/src/coding/files.ts) 与 [`test/coding/edit-review.test.ts`](../../daemon/test/coding/edit-review.test.ts)
验证文件编辑审查、哈希保护与真实 diff 契约：

- **只读审查能力 `fs.edit.preview`**：与 `fs.edit` 共享参数校验、解码与 diff 渲染逻辑；在 64-stripe 文件锁内读取真实原文件原始字节，计算 canonical 绝对路径、原始 bytes SHA-256 与带真实行号的变更 diff，返回 `{path, diff, sha256}`；不执行文件写入。
- **全量 Diff 边界守卫**：若包含完整 diff 的结果序列化 UTF-8 字节数超过 details 限制（1 MiB），在预览及执行 commit 前直接抛出 `ToolInputRejectedError` 明确拒绝，决不静默截断变更内容。
- **审批冻结与保护**：服务端将 review 结果冻结在 `approvalJson.editReview`（`{path, diff, sha256}`）；客户端 ALLOW/DENY 接口保持不变。
- **执行期 `expectedFile` 守卫**：用户审批通过后，平台在 INVOKE 顶层携带 `expectedFile`（`{path, sha256}`）元数据下发给 Daemon（不修改模型参数 arguments）。Daemon 在文件 mutation 锁内核对当前磁盘文件的路径与 SHA-256：若文件在审查后被篡改或变动，立即拒绝写入（"File changed since edit review; nothing was written. Review a new edit before approving it."），决不将陈旧授权应用到新内容上。
- **结果与呈现去重**：write 与 edit 执行成功后，结果仅呈现简洁的短状态文案（“写入成功”/“编辑成功”），不再在成功结果中重复回显正文或完整 diff；edit 成功结果仅在 `resultJson.details.editReview`（Java 对象的 `detailsJson`）中承载权威 diff 与哈希（供自动放行/YOLO 模式展示真实变更）；执行失败时不隐藏真实错误。自动放行/YOLO 模式不强求 expectedFile，不制造伪审批记录。

核心实现位于 [`files.ts`](../../daemon/src/coding/files.ts)、[`commit.ts`](../../daemon/src/coding/commit.ts) 与 [`text.ts`](../../daemon/src/coding/text.ts)。
