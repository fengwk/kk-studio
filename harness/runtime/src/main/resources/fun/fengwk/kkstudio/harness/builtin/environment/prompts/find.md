Find files by glob pattern in an explicit path.

Usage:
- Use `find` to discover files by name or path pattern, not to search file contents.
- `path` is required; there is no implicit search root.
- The file search respects `.gitignore`: rules are resolved from `path` upward, including ancestor `.gitignore` files and the repository's `.git/info/exclude`.
- Use `grep` after `find` when you need content search inside the discovered scope.
- `timeout_seconds` is optional and only needed when a broad file discovery may legitimately take longer than expected.
- Do not search from broad roots such as `/`, `~`, or `$HOME`.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; results and errors are reported as absolute paths, and no directory is ever inferred from cwd, `HOME`, an environment root, or a previous call. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `find({ pattern: "*.ts", path: "/srv/project/packages/web/src" })`
- `find({ pattern: "*.java", path: "/srv/project/services/java/src", limit: 200 })`
- `find({ pattern: "*.md", path: "C:/src/project/docs", limit: 50 })`
