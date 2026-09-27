Go to the definition of a symbol at a given position.

Usage:
- Use `line` to identify the target line.
- `character` is optional. Default: `0`.
- `path` is required and should be a file path inside the target project/workspace, usually the file containing the symbol reference; it selects the relevant workspace/server.
- Prefer this for known-symbol navigation and third-party API inspection, not for broad repository text search.
- If definition lookup is unavailable for the selected server, the tool call returns a clear error; use `grep` or `read` to locate definitions manually.
- `workdir` is optional. Prefer an absolute `path` and omit it.
- Provide `workdir` only when `path` is relative: it must be an expanded absolute directory on the target daemon's file system, and no call inherits a previous directory, session default, environment root, cwd, or home directory.

Examples:
- `lsp_goto_definition({ path: "/srv/project/packages/web/src/example.ts", line: 45, character: 15 })`
- `lsp_goto_definition({ path: "src/example.ts", workdir: "C:/src/project", line: 45 })`
