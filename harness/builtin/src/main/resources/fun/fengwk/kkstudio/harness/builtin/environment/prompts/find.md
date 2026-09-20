Find files by glob pattern in an explicit path.

Usage:
- Use `find` to discover files by name or path pattern, not to search file contents.
- `path` is required; there is no implicit search root. Use `.` only when the intent really is the current working directory.
- The underlying file search respects `.gitignore`.
- Use `grep` after `find` when you need content search inside the discovered scope.
- `timeout_seconds` is optional and only needed when a broad file discovery may legitimately take longer than expected.
- Do not search from broad roots such as `/`, `~`, or `$HOME`.
- Every call must include `workdir`. It must be an expanded absolute directory on the target daemon's file system; no call inherits a previous directory, session default, environment root, cwd, or home directory.

Examples:
- `find({ pattern: "*.ts", path: "src", workdir: "/srv/project/packages/web" })`
- `find({ pattern: "*.java", path: "src", workdir: "/srv/project/services/java", limit: 200 })`
- `find({ pattern: "*.md", path: "docs", workdir: "C:/src/project", timeout_seconds: 120 })`
