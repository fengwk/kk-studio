Decompile a Java external class using JDTLS.

Usage:
- **Only works against `jdtls`.** If the selected workspace is not backed by `jdtls`, the tool call returns a clear error.
- Prefer this over shell-based JAR extraction or manual decompilation.
- Pass a raw `jdt://...` URI, a complete `lsp_workspace_symbols` or `lsp_goto_definition` output line that contains such a URI, a `file:` URI, or an absolute `.class` path on the target Environment's file system.
- `path` is required and must be an absolute path on the target Environment's file system; it should point to a `.java` file inside the target Java project/workspace and selects the relevant Java workspace. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory.
- Prefer the sequence `lsp_workspace_symbols` or `lsp_goto_definition` -> `lsp_java_decompile` for third-party Java classes; this is the most efficient path for inspecting JAR-provided sources.
- Use this only for Java external definitions; for local source files, prefer `read` or `lsp_goto_definition` directly.
- `target` must be a `jdt://` URI, a complete symbol/definition output line containing one, a `file:` URI, or an absolute `.class` path. A relative class path is rejected.

Examples:
- `lsp_java_decompile({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", target: "jdt://contents/java.base/java/lang/String.class?..." })`
- `lsp_java_decompile({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", target: "/srv/project/services/java/build/classes/java/main/com/acme/App.class" })`
- `lsp_java_decompile({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", target: "String (Class) - jdt://contents/java.base/java/lang/String.class?..." })`
- `lsp_java_decompile({ path: "C:/src/project/src/main/java/com/acme/App.java", target: "C:/src/project/build/classes/java/main/com/acme/App.class" })`
