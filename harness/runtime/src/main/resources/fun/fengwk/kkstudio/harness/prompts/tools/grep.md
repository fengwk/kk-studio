Search file contents and return each matching line with its path and line number.

Usage:
- `path` must be an absolute directory or file path on the target Environment. There is no implicit search root. Avoid broad roots such as `/` or the user's home directory.
- `pattern` searches content as a regular expression by default. Use `literal=true` for exact text and `ignore_case=true` to ignore case in the content, not in `glob`. For cross-line matches, use a newline in `pattern` or, in regex mode, a `\n` escape or `(?s)` with `.`. There is no separate `multiline` argument.
- Optional `glob` filters files in a directory search using the same rules as `find.glob`: `*.ts` matches at any depth, `src/*.ts` starts at `path`, and `**/src/*.ts` includes nested `src` directories. A leading `./` does not change the scope, and `./` alone is invalid. When `path` names a single file, `glob` is not used.
- `.gitignore` and `.git/info/exclude` apply within their Git repository; nested repositories use their own rules. Outside a repository, only `.gitignore` files at or below `path` apply. Hidden files are included; `.git` and symbolic links encountered during traversal are skipped.
- `glob` cannot re-include ignored files. If nothing matches, the result may show up to 10 ignored paths as a hint. To search an ignored directory intentionally, pass the directory itself as `path`.
- Directory searches return paths relative to `path`; single-file searches report an absolute path. Treat matches as candidate locations. For editing context, resolve the path if needed and call `read` near the returned line number.
- Matching uses decoded text and the same line numbers as `read`. A directory search skips binary files and reports files whose declared encoding cannot be used. A direct search of such a file returns an error.
- Narrow the path, file glob, or content pattern first. Set `timeout_seconds` only if the default 15 seconds is not enough for a necessary search.

Examples:
- `grep({ path: "/srv/project", pattern: "TODO", literal: true, glob: "src/*.ts" })` searches files directly inside `/srv/project/src`.
- `grep({ path: "/srv/project", pattern: "TODO", literal: true, glob: "**/src/*.ts" })` also searches direct files in folders such as `/srv/project/pkg/src`.
- `grep({ path: "C:/src/project/src", pattern: "start\\nend" })` matches across two lines using a regular expression.
