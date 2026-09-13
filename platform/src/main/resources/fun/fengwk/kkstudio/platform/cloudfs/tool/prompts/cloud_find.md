# cloud_find

Find files and directories in Cloud File System by glob pattern.

- `pattern`: glob pattern (e.g. `*.md`, `**/*.java`, `docs/**/*.txt`).
- `path`: search root directory (canonical CFS path, e.g. `/` or `/knowledge`).
- Does not scan `/.artifacts`.
- Early stops when `limit + 1` matches are found.
