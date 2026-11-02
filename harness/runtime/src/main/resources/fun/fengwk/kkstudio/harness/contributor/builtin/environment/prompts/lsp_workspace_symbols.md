Search symbols across the workspace by name.

Usage:
- Use this for known-symbol lookup when LSP is available.
- `path` is required and must be an absolute path on the target Environment's file system; it should point to a file inside the target project/workspace and selects the relevant workspace/server. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory.
- Prefer this when you already know or strongly suspect the symbol name.
- For broad repository source search, prefer `grep` because it is simpler and usually faster.
- If workspace symbol search is unavailable for the selected server, the tool call returns a clear error; use `grep` or `find` instead.

Examples:
- `lsp_workspace_symbols({ path: "/srv/project/services/java/src/main/java/com/acme/App.java", query: "UserService", limit: 20 })`
- `lsp_workspace_symbols({ path: "C:/src/project/src/example.ts", query: "createDemoDirectory" })`
