按路径读取文本文件、目录或受支持的图片。

用法：
- 编辑已有文本文件前，使用 `read`。
- 对于文本文件，结果先是一个小 header（`path`、`ends_with_newline` 和 `lsp`），然后是空行，再后面是 `number|content` 格式的编号行。
- 编号行第一个 `|` 后的文本才是文件内容；header 行和编号列不属于文件内容。
- `ends_with_newline: yes` 表示文件以换行符结束；虽然 `read` 不会额外输出一个编号空行表示它。
- 使用 `offset` 和 `limit` 分块读取大文件。继续使用后续 offset 覆盖剩余内容；只有需要某个区域的局部上下文时，才扩大读取窗口。
- 文件是当前任务核心对象时，分块持续读取，直到覆盖整个相关文件。
- 读取目录时使用 `read`，不要使用 `bash ls`。

参数：
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `offset`（可选，从 1 开始的行偏移，默认：1）
- `limit`（可选，文本行数上限，默认：200，最大：2000）

示例：
- `read({ path: "src/example.ts", workdir: "packages/web" })`
- `read({ path: "src/example.ts", workdir: "services/api", offset: 120, limit: 40 })`
- `read({ path: "." })`
- `read({ path: "src/" })`
