Read a text file, directory, or supported image by path.

Usage:
- Use `read` before editing an existing text file.
- For text files, the result starts with a small header (`path`, `ends_with_newline`, and `lsp`), then a blank line, then numbered lines in `number|content` form.
- Only the text after the first `|` on a numbered line is file content; the header lines and number column are not part of the file.
- `ends_with_newline: yes` means the file ends with a newline, even though `read` does not add an extra numbered blank line to represent it.
- Use `offset` and `limit` to read large files in chunks. Continue with subsequent chunks to cover new content; only read a wider window around a region when you need more local context.
- When a file is central to the task, keep reading in chunks until you have covered the whole relevant file.
- Lines longer than 2000 code points are bounded to at most 2000 code points per read. The numbered body contains only exact file content (never synthetic truncation markers); metadata outside the numbered body reports the selected column range and the next-call hint.
- Use `column_offset` with `limit=1` to read subsequent fragments of a long line.
- Use `read` on directories instead of `bash ls`.
- Every call must include `workdir`. It must be an expanded absolute directory on the target daemon's file system; no call inherits a previous directory, session default, environment root, cwd, or home directory.

Examples:
- `read({ path: "src/example.ts", workdir: "/srv/project/packages/web" })`
- `read({ path: "src/example.ts", workdir: "/srv/project/services/api", offset: 120, limit: 40 })`
- `read({ path: "src/bundle.js", workdir: "/srv/project", offset: 1, limit: 1, column_offset: 2001 })`
- `read({ path: ".", workdir: "/srv/project" })`
- `read({ path: "src/", workdir: "C:/src/project" })`
- `read({ path: "screenshot.png", workdir: "/tmp/agent-artifacts" })`
