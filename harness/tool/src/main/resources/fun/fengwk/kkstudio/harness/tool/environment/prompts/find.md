在明确指定的路径下，按 glob pattern 查找文件。

用法：
- 使用 `find` 按文件名或路径 pattern 发现文件，不要用它搜索文件内容。
- `path` 必填；不存在隐式搜索根。只有目标确实是当前工作目录时才使用 `.`。
- 底层文件搜索遵守 `.gitignore`。
- 需要在发现的范围内搜索内容时，先使用 `find`，再使用 `grep`。
- `timeout_seconds` 可选；只有较大范围的文件发现确实可能耗时较长时才需要设置。

参数：
- `pattern`（必填）
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `limit`（可选，默认：1000）
- `timeout_seconds`（可选，无默认值）

示例：
- `find({ pattern: "*.ts", path: "src", workdir: "packages/web" })`
- `find({ pattern: "*.java", path: "src", workdir: "services/java", limit: 200 })`
- `find({ pattern: "*.md", path: "docs", timeout_seconds: 30 })`
