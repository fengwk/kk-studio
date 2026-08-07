在文本文件中执行精确字符串替换。

用法：
- 编辑已有文本文件前，优先使用 `read`，以便复制文件中的精确原文。
- `read` 的文本输出先是一个小 header（`path`、`ends_with_newline` 和 `lsp`），然后是 `number|content` 格式的编号行。
- 从 `read` 复制文本时，只使用每个编号行第一个 `|` 后面的文件内容。绝不能把编号列或 `|` 本身放进 `old_string` 或 `new_string`。
- 如果 `read` 报告 `ends_with_newline: yes`，记住文件以换行符结束；虽然 `read` 不会额外输出一个编号空行表示它。
- 已有文本文件优先使用 `edit`；新文件或有意进行整文件替换时使用 `write`。
- 如果文件中找不到 `old_string`，edit 会失败并返回错误 `"Could not find old_string"`。
- 如果 `old_string` 出现多次，edit 会失败并报告匹配数量，例如 `"Found N exact matches"`。此时应增加上下文使字符串唯一，或使用 `replace_all` 替换所有匹配。
- 对整文件范围的精确重命名或所有重复出现都应修改的场景，使用 `replace_all`。

参数：
- `path`（必填）：要编辑的文件路径（相对或绝对路径）。
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）。
- `old_string`（必填）：要替换的精确文本。必须与文件内容完全匹配，包括空白和缩进；除非 `replace_all` 为 true，否则必须在文件中唯一出现。
- `new_string`（必填）：替换文本，且必须与 `old_string` 不同。
- `replace_all`（可选，默认 false）：替换 `old_string` 的所有精确匹配。
