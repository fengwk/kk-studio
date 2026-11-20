Find files by glob in an explicit directory. Results are relative to the search directory.

Usage:
- `path` must be an absolute directory path on the target Environment, such as `/srv/project` or `C:/src/project`. There is no implicit search root. Avoid broad roots such as `/` or the user's home directory.
- `pattern` matches file paths, not file contents. `*.ts` matches file names at any depth under `path`; `src/*.ts` matches only direct files inside its `src`; `**/src/*.ts` also matches direct files inside nested `src` directories. In a path glob, `*` does not cross `/`; `**/` allows zero or more directory levels. A leading `./` does not change the scope, and `./` alone is invalid.
- `.gitignore` and `.git/info/exclude` apply within their Git repository; nested repositories use their own rules. Outside a repository, only `.gitignore` files at or below `path` apply. Hidden files are included; `.git` and symbolic links encountered during traversal are skipped.
- Ignored paths never appear as matches. If nothing matches, the result may show up to 10 ignored paths as a hint. To search an ignored directory intentionally, pass the directory itself as `path`.
- For a follow-up `read`, resolve the returned relative path against the same `path`. Use `grep` instead when you need to match file contents.
- Narrow the scope first. Set `timeout_seconds` only if the default 15 seconds is not enough for a necessary search.

Examples:
- `find({ path: "/srv/project", pattern: "src/*.ts" })` finds direct TypeScript files in `/srv/project/src`.
- `find({ path: "/srv/project", pattern: "**/src/*.ts" })` also includes direct TypeScript files in folders such as `/srv/project/pkg/src`.
- `find({ path: "C:/src/project/docs", pattern: "*.md", limit: 50 })` finds Markdown files at any depth under `docs`.
