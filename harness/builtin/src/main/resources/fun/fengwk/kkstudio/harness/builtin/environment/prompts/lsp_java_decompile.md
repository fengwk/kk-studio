Decompile a Java external class using JDTLS.

Usage:
- **Only works against `jdtls`.** If the selected workspace is not backed by `jdtls`, the tool call returns a clear error.
- Prefer this over shell-based JAR extraction or manual decompilation.
- Pass a raw `jdt://...` URI or a full workspace symbol / definition output line when available.
- `path` is required and should be a file path inside the target Java project/workspace, usually the file you are currently working from; it selects the relevant Java workspace.
- Prefer the sequence `lsp_workspace_symbols` or `lsp_goto_definition` -> `lsp_java_decompile` for third-party Java classes; this is the most efficient path for inspecting JAR-provided sources.
- Use this only for Java external definitions; for local source files, prefer `read` or `lsp_goto_definition` directly.
- `workdir` is optional. Prefer an absolute `path` and omit it.
- Provide `workdir` only when `path` or `target` is relative: it must be an expanded absolute directory on the target daemon's file system, and no call inherits a previous directory, session default, environment root, cwd, or home directory.
- Prefer an absolute class `target`. A relative class path is resolved only against an explicit `workdir`, and fails without one.

Examples:
- `lsp_java_decompile({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", target: "jdt://contents/java.base/java/lang/String.class?..." })`
- `lsp_java_decompile({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", target: "/srv/project/services/java/build/classes/java/main/com/acme/App.class" })`
- `lsp_java_decompile({ path: "src/main/java/com/acme/App.java", workdir: "C:/src/project", target: "String (Class) - jdt://contents/java.base/java/lang/String.class?..." })`
