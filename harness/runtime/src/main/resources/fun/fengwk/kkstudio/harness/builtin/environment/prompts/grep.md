Search file contents and return matching lines.

Usage:
- Use `grep` for repository content search.
- Always pass an explicit `path`.
- Prefer narrowing the path or pattern before increasing `timeout_seconds`. The default timeout is usually sufficient, and explicitly setting a timeout is not recommended unless a broader scan is truly necessary.
- Treat `grep` results as candidate locations, not editing context. After `grep`, use `read` with targeted `offset`/`limit` to inspect enough surrounding code before editing.
- The content search respects `.gitignore`: rules are resolved from `path` upward, including ancestor `.gitignore` files and the repository's `.git/info/exclude`.
- When `path` is a single binary file, `grep` returns a clear error instead of binary output.
- `pattern` is a regular expression by default; use `literal=true` for exact text.
- `multiline=true` enables matching across line breaks. Use it when the pattern contains an actual newline or the regex newline escape `\n`; in JSON/tool-call payloads that regex escape is written as `\\n`.
- Do not search from broad roots such as `/`, `~`, or `$HOME`.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; results and errors are reported as absolute paths, and no directory is ever inferred from cwd, `HOME`, an environment root, or a previous call. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `grep({ pattern: "createDemoDirectory", path: "/srv/project/packages/web/src", literal: true })`
- `grep({ pattern: "create.*Directory", path: "/srv/project/services/api/src", ignore_case: true })`
- `grep({ pattern: "TODO", path: "/srv/project/src", include: "**/*.ts", timeout_seconds: 120 })`
- `grep({ pattern: "start\\nend", path: "C:/src/project/src", multiline: true })`
- `grep({ pattern: "TODO", path: "C:/src/project/src", include: "**/*.ts" })`
