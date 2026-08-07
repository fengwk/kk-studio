使用 JDTLS 反编译 Java 外部类。

用法：
- **仅支持 `jdtls`。** 如果选定 workspace 不是由 `jdtls` 提供支持，工具调用会返回清晰错误。
- 相比通过 Shell 提取 JAR 或手动反编译，优先使用此工具。
- 如果可用，传入原始 `jdt://...` URI，或完整的 workspace symbol / definition 输出行。
- `path` 必填，应当是目标 Java 项目/workspace 内的文件路径，通常是当前正在处理的文件；它用于选择对应的 Java workspace。
- 检查 JAR 提供的第三方 Java 类时，优先使用 `lsp_workspace_symbols` 或 `lsp_goto_definition` -> `lsp_java_decompile` 的顺序；这是最高效的源码检查路径。
- 仅对 Java 外部定义使用此工具；对于本地源文件，直接使用 `read` 或 `lsp_goto_definition`。

参数：
- `path`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析相对路径）
- `target`（必填）

示例：
- `lsp_java_decompile({ path: "src/main/java/com/acme/App.java", workdir: "services/java", target: "jdt://contents/java.base/java/lang/String.class?..." })`
- `lsp_java_decompile({ path: "src/main/java/com/acme/App.java", target: "String (Class) - jdt://contents/java.base/java/lang/String.class?..." })`
