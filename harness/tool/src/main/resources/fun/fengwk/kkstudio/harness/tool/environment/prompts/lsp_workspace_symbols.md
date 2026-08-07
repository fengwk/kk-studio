按名称在 workspace 内搜索符号。

用法：
- LSP 可用时，用此工具查找已知符号。
- `path` 必填，应当是目标项目/workspace 内的文件路径，通常是当前正在处理的文件；它用于选择对应的 workspace/server。
- 已知或高度怀疑符号名称时，优先使用此工具。
- 大范围仓库源码搜索优先使用 `grep`，因为它更简单且通常更快。
- 如果选定 server 不支持 workspace 符号搜索，工具调用会返回清晰错误；此时改用 `grep` 或 `find`。

参数：
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `query`（必填）
- `limit`（可选，输出条数上限，默认：50）

示例：
- `lsp_workspace_symbols({ path: "src/main/java/com/acme/App.java", workdir: "services/java", query: "UserService", limit: 20 })`
- `lsp_workspace_symbols({ path: "src/example.ts", query: "createDemoDirectory" })`
