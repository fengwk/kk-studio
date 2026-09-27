Find files by glob pattern in an explicit path.

Usage:
- Use `find` to discover files by name or path pattern, not to search file contents.
- `path` is required; there is no implicit search root. Use `.` only when the intent really is a `workdir`-relative search.
- The file search respects `.gitignore`: rules are resolved from `path` upward, including ancestor `.gitignore` files and the repository's `.git/info/exclude`, independently of `workdir`.
- Use `grep` after `find` when you need content search inside the discovered scope.
- `timeout_seconds` is optional and only needed when a broad file discovery may legitimately take longer than expected.
- Do not search from broad roots such as `/`, `~`, or `$HOME`.
- Every call must include `workdir` when `path` is relative; an absolute `path` may omit it. `workdir` must be an expanded absolute directory on the target daemon's file system; no call inherits a previous directory, session default, environment root, cwd, or home directory.
- Without `workdir`, results and errors are reported as absolute paths; directories are never inferred from cwd, `HOME`, an environment root, or a previous call.

Examples:
- `find({ pattern: "*.ts", path: "src", workdir: "/srv/project/packages/web" })`
- `find({ pattern: "*.java", path: "src", workdir: "/srv/project/services/java", limit: 200 })`
- `find({ pattern: "*.md", path: "C:/src/project/docs", limit: 50 })`
