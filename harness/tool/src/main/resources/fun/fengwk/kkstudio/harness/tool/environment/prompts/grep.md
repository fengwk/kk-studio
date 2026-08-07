搜索文件内容并返回匹配行。

用法：
- 使用 `grep` 搜索仓库内容。
- 始终传入明确的 `path`。
- 增加 `timeout_seconds` 前，优先缩小 path 或 pattern。默认超时通常足够；除非确实需要大范围扫描，否则不建议显式设置。
- 将 `grep` 结果视为候选位置，而不是编辑上下文。执行 `grep` 后，在编辑前使用带目标 `offset`/`limit` 的 `read` 检查足够的周边代码。
- 内容搜索遵守 `.gitignore`。
- 当 `path` 是单个二进制文件时，`grep` 返回清晰的错误，而不是输出二进制内容。
- `multiline=true` 启用跨行匹配。

参数：
- `pattern`（必填）
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `include`（可选）
- `ignore_case`（可选，默认：false）
- `literal`（可选，默认：false）
- `multiline`（可选，默认：false）
- `limit`（可选，默认：100）
- `timeout_seconds`（可选，默认：15）

示例：
- `grep({ pattern: "createDemoDirectory", path: "src", workdir: "packages/web", literal: true })`
- `grep({ pattern: "create.*Directory", path: "src", workdir: "services/api", ignore_case: true })`
- `grep({ pattern: "TODO", path: "src", include: "**/*.ts", timeout_seconds: 30 })`
