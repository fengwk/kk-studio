Go to the definition of a symbol at a given position.

Usage:
- Use `line` to identify the target line.
- `character` is optional. Default: `0`.
- `path` is required and must be an absolute path on the target Environment's file system; it should point to the file containing the symbol reference and selects the relevant workspace/server. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory.
- Prefer this for known-symbol navigation and third-party API inspection, not for broad repository text search.
- If definition lookup is unavailable for the selected server, the tool call returns a clear error; use `grep` or `read` to locate definitions manually.

Examples:
- `lsp_goto_definition({ path: "/srv/project/packages/web/src/example.ts", line: 45, character: 15 })`
- `lsp_goto_definition({ path: "C:/src/project/src/example.ts", line: 45 })`
