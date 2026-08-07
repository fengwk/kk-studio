创建文本文件，或有意覆盖整个文本文件。

用法：
- 仅在创建新文件或有意进行整文件替换时使用 `write`。
- 对已有文件优先使用 `edit`；只有整文件替换或大约 60% 以上内容需要重写时才使用 `write`，不要用它执行可以由 `edit` 安全完成的局部修改。
- 提供完整内容，不要使用 `...` 等占位符或省略任何部分。
- `write` 只返回成功消息。如果需要检查写入后的文件内容，随后使用 `read`。

参数：
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `content`（必填）

示例：
- `write({ path: "src/new-module.ts", content: "export const demo = 1;\n" })`
- `write({ path: "docs/new-template.md", workdir: "packages/web", content: "# New template\n\nComplete file content.\n" })`
