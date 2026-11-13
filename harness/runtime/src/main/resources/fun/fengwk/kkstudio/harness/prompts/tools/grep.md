Search file contents and return matching lines.

Usage:
- Use `grep` for repository content search.
- Always pass an explicit `path`.
- Prefer narrowing the path or pattern before increasing `timeout_seconds`. The default timeout is usually sufficient, and explicitly setting a timeout is not recommended unless a broader scan is truly necessary.
- Treat `grep` results as candidate locations, not editing context. After `grep`, use `read` with targeted `offset`/`limit` to inspect enough surrounding code before editing.
- `.gitignore` handling follows Git. Inside a repository, the `.gitignore` files from the repository root down to each file and the repository's `.git/info/exclude` apply; a nested repository uses only its own rules; `.gitignore` files above the repository root never apply. Outside any repository, only `.gitignore` files at or below `path` apply. Global Git excludes are not used.
- Ignored files are left out of the results without any notice, so "not found" does not prove a file or text does not exist in an ignored location.
- Hidden files are included, `.git` is skipped, and symbolic links are not followed.
- To search an ignored directory, pass that directory as `path`; rules from above it then no longer apply. A file passed directly as `path` is always searched.
- If no matches are found because ignore rules skipped entries, the result adds a hint such as `[No matches. Not searched because of ignore rules: /repo/node_modules/, /repo/.env.local, … and N more]` (up to 10 absolute paths).
- When `path` is a single binary file, `grep` returns a clear error instead of binary output.
- `grep` matches decoded text, so BOM-marked and declared files (for example UTF-16 or GBK) match the characters you see in `read`, and reported line numbers equal `read`'s. Binary files are skipped silently. Files whose text encoding cannot be determined are skipped and listed at the end, such as `[Not searched because the text encoding could not be determined: /repo/notes.txt, … and 2 more]` (up to 10 absolute paths).
- `pattern` is a regular expression by default; use `literal=true` for exact text.
- `glob` uses the same glob rules as `find` and cannot re-include ignored files.
- A pattern that contains an actual newline, a `\\n` escape, or `(?s)` with `.` can match across lines.
- Do not search from broad roots such as `/`, `~`, or `$HOME`.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; results and errors are reported as absolute paths, and no directory is ever inferred from cwd, `HOME`, an environment root, or a previous call. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `grep({ pattern: "createDemoDirectory", path: "/srv/project/packages/web/src", literal: true })`
- `grep({ pattern: "create.*Directory", path: "/srv/project/services/api/src", ignore_case: true })`
- `grep({ pattern: "TODO", path: "/srv/project/src", glob: "**/*.ts", timeout_seconds: 120 })`
- `grep({ pattern: "start\\nend", path: "C:/src/project/src" })`
- `grep({ pattern: "TODO", path: "C:/src/project/src", glob: "**/*.ts" })`
