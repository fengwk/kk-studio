# 文件工具

## 1. 两套明确的工具

Environment 与 Cloud File System 使用相同的五项操作语义，但路径空间和模型名称不同：

| 操作 | Environment 模型名称 | Cloud 模型名称 |
| --- | --- | --- |
| 读取文件/目录 | `read` | `cloud_read` |
| 创建或完整覆盖文本 | `write` | `cloud_write` |
| 精确替换文本 | `edit` | `cloud_edit` |
| 按 glob 找路径 | `find` | `cloud_find` |
| 按 literal/regex 找内容 | `grep` | `cloud_grep` |

不能根据 Agent 是否绑定 Environment 把 `read` 动态路由到 Cloud，也不能让一个 schema 同时接受 host path 与 virtual path。

```text
read(path, workdir)       -> target Daemon OS filesystem
cloud_read(path)          -> Platform Cloud File System
```

## 2. 稳定身份

目标 Environment capability ID：

```text
fs.read
fs.write
fs.edit
fs.find
fs.grep
process.exec
lsp.goto-definition
lsp.workspace-symbols
lsp.java-decompile
```

删除 `fs.apply-edit` 和 `fs.search` 名称，分别由 `fs.edit` 和 `fs.grep` 一次性替代。协议、Daemon catalog、schema 文件、Builtin mapping、测试和文档在同一版本切换，不保留旧 capability alias。

Cloud Tool：

```text
AgentToolId       model name
cloud.read        cloud_read
cloud.write       cloud_write
cloud.edit        cloud_edit
cloud.find        cloud_find
cloud.grep        cloud_grep
```

`ToolDescriptor.name` 继续遵守 Provider 兼容的字母、数字、下划线和连字符规则，不为点号命名修改底层 Provider contract。

## 3. Path 与 workdir

### 3.1 Environment

五个文件工具、`process.exec` 和三个 LSP 工具的具体 arguments 都要求：

- `workdir` 必填；
- 是目标 Daemon OS 上已展开的绝对目录；
- Backend 只按 READY 中冻结的 `DaemonOperatingSystem` 做 lexical shape 校验；
- Daemon 执行前验证真实存在、是目录且可访问；
- 不从 Agent、Chat、Project、Issue、Session、Environment、父 Agent 或前次调用补齐；
- 不允许 `~`、环境变量、空值或相对 workdir。

文件工具中的 `path`：

- 必填；
- 相对 path 基于本次 `workdir`；
- 绝对 path 直接定位目标；
- 不把 `workdir` 当作沙箱或 allowlist；
- `find`/`grep` 的 ignore 根、输出相对根都固定为本次 `workdir`，不使用 Daemon 启动目录或 Environment root。

Environment 工具输出的文件路径统一相对 `workdir`，使用 `/` 展示分隔符，确保可直接传给同一 workdir 下的后续 `read`/`edit`。

### 3.2 Cloud

Cloud 五工具的 `path` 必须是 CFS canonical 虚拟绝对路径：

- 无 `workdir`；
- 不接受相对路径；
- 输出始终是 canonical 虚拟绝对路径；
- 详细规则由 [Cloud File System](cloud-file-system.md)定义。

## 4. 模型参数纠错

部分模型会把 `path` 写成 `file`、`filePath` 或 `file_path`。对上述十个 path-bearing 模型工具执行一次静默、单向纠错：

```text
path 不存在
AND file/filePath/file_path 恰好出现一个
-> 删除 alias，写入 canonical path
```

规则：

- 在 JSON schema 校验前执行。
- 只对已知内建 Tool model name 启用，不污染 MCP 或第三方 Contributor schema。
- `path` 已存在时不改写；残留 alias 由 additionalProperties 校验拒绝。
- 多个 alias 同时出现时不猜测，严格拒绝。
- alias value 不做类型转换，由 canonical schema 校验。
- Provider 原始 ToolCall 继续原样进入 durable assistant history，以保证请求重放精确；
  transient executable ToolCall、审批预览和 renderer 统一使用归一化后的 `path`。
- alias 不出现在 schema、prompt 或文档示例中。

实现落在 `harness/tool` 的 model Tool argument normalizer，由 `ToolCall.validateFor` 在通用数字/null normalization 之前调用。Environment 管理 capability、MCP 和非模型调用不使用该兼容层。

不做以下字符串级猜测：

- 不删除 path 前导 `@`；
- 不展开 `~`、`$HOME` 或 `%VAR%`；
- 不在 Backend 上统一替换 `/`、`\`；
- 不 trim 合法文件名中的首尾字符；
- 不把 `read({})` 默认成 `.` 或 `/`。

## 5. `read` / `cloud_read`

### 5.1 参数

Environment：

```json
{
  "path": "src/example.ts",
  "workdir": "/srv/project",
  "offset": 1,
  "limit": 200
}
```

Cloud：

```json
{
  "path": "/knowledge/projects/kk-studio/example.md",
  "offset": 1,
  "limit": 200
}
```

- `offset`：1-based，默认 1。
- `limit`：正整数，默认 200，最大 2000。
- 对文本表示物理行窗口；对目录表示排序后的直接 child 窗口。
- path 是文件还是目录由执行时事实决定，不增加 `mode` 参数。

### 5.2 文本输出

Environment：

```text
path: src/example.ts
ends_with_newline: yes
lsp: supported (typescript)

120|...
121|...

[Showing lines 120-159 of 420. Re-run read with offset=160 to continue.]
```

Cloud：

```text
path: /knowledge/projects/kk-studio/example.md
kind: text
revision: 8
ends_with_newline: yes

120|...
121|...
```

共同规则：

- 响应字节预算小于统一 Tool inline limit；即使调用者要求更多行，也在完整行边界提前停止并给出下一 offset。
- 每个物理行最多展示 2000 Unicode code points。
- 超长行追加 `(line truncated to 2000 chars)`，并在结尾说明存在 line truncation。
- 本轮不增加 `column_offset`；反复读取同一超长行不会取得后续列，这是明确限制。
- `offset > totalLines` 返回空窗口和总行数，不静默回到末尾。
- 保留 1-based 行号，便于 grep、编译器和 LSP 结果直接复用。

### 5.3 文件边界

Environment text read：

- 单文件硬上限 64 MiB；
- 支持项目现有且可无损 round-trip 的文本编码；
- 保留 BOM 和原始行结束事实供 write/edit；
- NUL、非法解码、无法识别的二进制明确失败；
- 不默认把未知 bytes 当 UTF-8 Resource。

Image：

- 根据实际 bytes 检测受支持 MIME，不只信任扩展名；
- 模型支持 IMAGE 时返回 Resource；
- 不支持时返回确定性 metadata，不伪装成文本。

Cloud editable TEXT 已是 PostgreSQL Unicode；Cloud BLOB 的读取边界见 Cloud File System。

## 6. `write` / `cloud_write`

Environment：

```json
{
  "path": "src/new.ts",
  "content": "export const value = 1;\n",
  "workdir": "/srv/project"
}
```

- 创建缺失 parent directories。
- 不存在则创建，存在的普通文本文件则完整覆盖。
- 拒绝目录、symlink race 后的非普通目标和不可无损编码内容。
- 覆盖时保留原文件编码、BOM 和换行风格。
- 同一路径 mutation 在 Daemon 内串行；不同路径不使用全局锁。

Cloud：

```json
{
  "path": "/knowledge/projects/kk-studio/new.md",
  "content": "# Title\n",
  "expected_revision": 0
}
```

- `expected_revision` 必填；0=create，N=replace current N。
- 创建时原子建立缺失 DIRECTORY ancestors。
- 只处理 TEXT，不以 base64 接受二进制。
- 成功返回 canonical path 和新 revision。

## 7. `edit` / `cloud_edit`

共同参数：

```json
{
  "path": "src/app.ts",
  "old_string": "const port = 80",
  "new_string": "const port = 8080",
  "replace_all": false
}
```

Environment 额外要求 `workdir`；Cloud 额外要求 `expected_revision`。

规则：

- `old_string` 必须非空。
- 默认要求恰好一个匹配；0 个返回 `Could not find old_string in <path>`，多个要求更长上下文或 `replace_all=true`。
- `replace_all=true` 原子替换全部非重叠 literal occurrences。
- Environment 保留编码、BOM 和行结束；Cloud 创建 N+1 revision。
- 先在内存/事务候选上完成全部校验，再写入目标。
- 成功输出带行号的有界 contextual diff，而不是“已替换 N 处”的摘要。
- diff 自身必须低于 Tool inline limit；过大时缩小每处 context 并说明省略，不生成另一个 Tool Artifact。

## 8. `find` / `cloud_find`

参数：

```json
{
  "pattern": "**/*.java",
  "path": "src",
  "limit": 200,
  "timeout_seconds": 15
}
```

Cloud path 使用虚拟绝对路径且无 workdir。

规则：

- 只发现 path，不读取文件内容。
- `*` 不跨 `/`，`**` 可以跨目录，`?` 匹配单个字符；候选路径统一以 `/` 表示。
- pattern 不含 `/` 时匹配 basename；含 `/` 时匹配搜索起点下的相对路径。
- Environment 遵守从 invocation `workdir` 开始解析的分层 `.gitignore`，跳过 `.git` metadata。
- Cloud 不应用 `.gitignore`，不枚举 `/.artifacts`。
- 输出排序确定，Environment 返回 workdir-relative path，Cloud 返回 canonical absolute path。
- visitor 每发现一项即检查 cancel/deadline；检测到 `limit + 1` 立即停止。
- 达到 limit 明确说明 refine pattern；不把完整全集先收集进内存。

## 9. `grep` / `cloud_grep`

参数：

```json
{
  "pattern": "create.*Directory",
  "path": "src",
  "include": "**/*.java",
  "ignore_case": true,
  "literal": false,
  "multiline": false,
  "limit": 100,
  "timeout_seconds": 15
}
```

### 9.1 Regex

- `literal=true` 执行普通 substring search，不进入 regex engine。
- regex 使用 Google RE2/J。
- 接受 RE2/ripgrep 可移植线性子集。
- lookaround、backreference、possessive quantifier 和其它 RE2 不支持语法在编译期拒绝。
- 不把模型 pattern 交给 `java.util.regex.Pattern`、PostgreSQL ARE 或前端 JS regex。
- `ignore_case` 使用 Unicode case-insensitive 语义。
- `multiline=false` 逐物理行匹配；`multiline=true` 可跨换行匹配并报告覆盖行。

Environment Daemon 与 Platform 分别直接依赖 RE2/J；不为一个 regex helper
或测试资源建立新 module。两端用相同的固定 accept/reject/match cases 验证公开语义。

### 9.2 搜索

- Environment 从显式 path 流式遍历，读取上限内文本并遵守 invocation workdir 的 ignore 规则。
- Cloud 遍历当前 TEXT revisions；精确
  `/.artifacts/tool-results/{threadId}/{invocationId}.txt` 路径可以扫描单个 UTF-8
  Blob。
- 搜索过程检查 interrupt/cancel/deadline。
- 命中 `limit + 1` 立即终止，不继续遍历剩余文件。
- 无匹配固定返回 `No matches found`。

### 9.3 输出

Environment：

```text
src/App.java:42:matching content
```

Cloud：

```text
/knowledge/projects/kk-studio/App.java:42:matching content
```

- 普通匹配行最多展示 500 Unicode code points。
- excerpt 必须包含实际 match；不能固定只截该行前 500 字符。
- 超长匹配行说明已截断，并保留 path、line 和匹配附近文本。
- multiline 对每个被覆盖物理行最多输出一次。

## 10. `process.exec`

模型名称继续为 `bash`，capability ID 为 `process.exec`：

- `command` 与绝对 `workdir` 必填。
- `timeout_seconds` 默认 120，最大 3600。
- command 原样交给配置的 shell，不解析、重写或建立命令白名单。
- cwd 只对本次 ProcessBuilder 生效。
- stdout/stderr 合并流可以发送有界 live partial；终态完整输出进入统一 output finalizer。
- 从读取第一字节开始流式 spool，不能使用无上限 `ByteArrayOutputStream`。
- cancel/timeout 终止完整 process tree；不能宣称已回滚进程副作用。

## 11. LSP

三个 LSP capability 都要求显式绝对 `workdir`：

- `path` 用于选择 workspace/server，relative path 基于 workdir。
- line 1-based，character 0-based，保持 LSP 约定。
- 返回的本地文件路径相对 invocation workdir。
- `lsp.java-decompile` 的 `target` 可以是 `jdt://`、workspace symbol output 或 class path，但 Backend 不自行解析目标 Daemon 文件。
- LSP bridge 不保存跨调用默认 workdir。

## 12. 执行结构

Daemon coding package收敛为：

```text
PathResolver            # target OS real path，调用内对象
TextFileCodec           # strict decode/encode/line endings
ReadCapability
WriteCapability
EditCapability
FindCapability
GrepCapability
ProcessCapability
Lsp*Capability
SearchWalker            # visitor/cancel/ignore
OutputSpool             # producer-side bounded memory/resource
```

- 删除把搜索全集返回 `List<Path>`、再统一排序/截断的 collector。
- 删除同时承担 binary detection、模型 preview 和 Resource persistence 的 Daemon `OutputLimiter`。
- Producer 只返回 inline logical content 或完整 Resource；模型展示由 Platform finalizer 唯一负责。
- 工具自己的 semantic limit 与终态 output externalization 使用不同字段，不能互相冒充。

## 13. 测试

### 13.1 共享契约

- 五工具 schema 与 prompt 一致。
- 1-based offset、默认/最大 limit、错误文案和输出 path 规则。
- path alias：三个单 alias 成功；canonical+alias、多 alias、错误类型失败。
- Daemon 和 Platform 分别覆盖相同的 RE2 accept/reject/match cases。

### 13.2 Environment

- Unix、Windows drive root、Windows UNC 和非法 workdir lexical cases。
- read directory/file/image/binary/64 MiB boundary/long-line marker/page byte budget。
- write/edit encoding、BOM、CRLF、atomicity、multi-match、diff。
- find/grep ignore、glob、match-centered excerpt、limit+1 early stop、cancel/deadline。
- process stream spool、exit code、timeout、cancel tree 和 hard output cap。
- 核心路径 JaCoCo line coverage 不低于 90%。

### 13.3 Cloud

- canonical virtual path 与 Environment path 完全隔离。
- text revision CAS、directory pagination、Blob media handling。
- find/grep absolute output、无 `.gitignore`、`.artifacts` 不可枚举且 Artifact
  exact-only。
- Cloud 五工具在 Agent 无 Environment 时仍进入冻结 ModelRequestSpec 并可执行。
