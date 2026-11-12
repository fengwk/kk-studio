Find files by glob pattern in an explicit path.

Usage:
- Use `find` to discover files by name or path pattern, not to search file contents.
- `path` is required; there is no implicit search root.
- `.gitignore` handling follows Git. Inside a repository, the `.gitignore` files from the repository root down to each file and the repository's `.git/info/exclude` apply; a nested repository uses only its own rules; `.gitignore` files above the repository root never apply. Outside any repository, only `.gitignore` files at or below `path` apply. Global Git excludes are not used.
- Ignored files are left out of the results without any notice, so "not found" does not prove a file or text does not exist in an ignored location.
- Hidden files are included, `.git` is skipped, and symbolic links are not followed.
- To search an ignored directory, pass that directory as `path`; rules from above it then no longer apply.
- If no matches are found because ignore rules skipped entries, the result adds a hint such as `[No matches. Not searched because of ignore rules: /repo/node_modules/, /repo/.env.local, … and N more]` (up to 10 absolute paths).
- Use `grep` after `find` when you need content search inside the discovered scope.
- `timeout_seconds` is optional and only needed when a broad file discovery may legitimately take longer than expected.
- Do not search from broad roots such as `/`, `~`, or `$HOME`.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; results and errors are reported as absolute paths, and no directory is ever inferred from cwd, `HOME`, an environment root, or a previous call. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `find({ pattern: "*.ts", path: "/srv/project/packages/web/src" })`
- `find({ pattern: "*.java", path: "/srv/project/services/java/src", limit: 200 })`
- `find({ pattern: "*.md", path: "C:/src/project/docs", limit: 50 })`
