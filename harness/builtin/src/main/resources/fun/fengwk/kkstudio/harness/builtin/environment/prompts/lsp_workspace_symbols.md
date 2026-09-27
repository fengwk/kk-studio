Search symbols across the workspace by name.

Usage:
- Use this for known-symbol lookup when LSP is available.
- `path` is required and should be a file path inside the target project/workspace, usually the file you are currently working from; it selects the relevant workspace/server.
- Prefer this when you already know or strongly suspect the symbol name.
- For broad repository source search, prefer `grep` because it is simpler and usually faster.
- If workspace symbol search is unavailable for the selected server, the tool call returns a clear error; use `grep` or `find` instead.
- `workdir` is optional. Prefer an absolute `path` and omit it.
- Provide `workdir` only when `path` is relative: it must be an expanded absolute directory on the target daemon's file system, and no call inherits a previous directory, session default, environment root, cwd, or home directory.

Examples:
- `lsp_workspace_symbols({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", query: "UserService", limit: 20 })`
- `lsp_workspace_symbols({ path: "src/example.ts", workdir: "C:/src/project", query: "createDemoDirectory" })`
