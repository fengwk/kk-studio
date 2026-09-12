Search symbols across the workspace by name.

Usage:
- Use this for known-symbol lookup when LSP is available.
- `path` is required and should be a file path inside the target project/workspace, usually the file you are currently working from; it selects the relevant workspace/server.
- Prefer this when you already know or strongly suspect the symbol name.
- For broad repository source search, prefer `grep` because it is simpler and usually faster.
- If workspace symbol search is unavailable for the selected server, the tool call returns a clear error; use `grep` or `find` instead.
- Every call must include `workdir`. It must be an expanded absolute directory on the target daemon's file system; no call inherits a previous directory, session default, environment root, cwd, or home directory.

Examples:
- `lsp_workspace_symbols({ path: "src/main/java/com/acme/App.java", workdir: "/srv/project/services/java", query: "UserService", limit: 20 })`
- `lsp_workspace_symbols({ path: "src/example.ts", workdir: "C:/src/project", query: "createDemoDirectory" })`
