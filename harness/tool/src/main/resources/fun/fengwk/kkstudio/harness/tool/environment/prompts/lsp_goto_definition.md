跳转到指定位置处符号的定义。

用法：
- 使用 `line` 指定目标行。
- `character` 可选，默认值为 `0`。
- `path` 必填，应当是目标项目/workspace 内的文件路径，通常是包含符号引用的文件；它用于选择对应的 workspace/server。
- 已知符号导航和第三方 API 检查优先使用此工具，不要用它进行大范围仓库文本搜索。
- 如果选定 server 不支持定义查找，工具调用会返回清晰错误；此时使用 `grep` 或 `read` 手动定位定义。

参数：
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `line`（必填，从 1 开始计数）
- `character`（可选，从 0 开始计数，默认：0）

示例：
- `lsp_goto_definition({ path: "src/example.ts", workdir: "packages/web", line: 45, character: 15 })`
- `lsp_goto_definition({ path: "src/example.ts", line: 45 })`
