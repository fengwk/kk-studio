Read a text file, directory, or supported image by path.

Usage:
- Use `read` before editing an existing text file.
- For text files, the result starts with a small header, then a blank line, then numbered lines in `number|content` form.
- Header order: `path`, `ends_with_newline`, `range`. When the result is truncated it also carries `truncated: yes`, `truncation_reason`, and `next`; otherwise those three fields are omitted.
- `range` uses 1-based positions `start_line:start_column-end_line:end_column`. `range: empty` means the requested start is past the end of the file (this also covers an empty file), so there is no body.
- `ends_with_newline` and `range` describe positions in the whole file, even when only a prefix was returned.
- Only the text after the first `|` on a numbered line is file content; the header lines and number column are not part of the file.
- `ends_with_newline: yes` means the file ends with a newline, even though `read` does not add an extra numbered blank line to represent it.
- `offset` (default 1) selects the first returned line; `limit` (default and maximum 2000) bounds the number of returned lines. The body is additionally bounded to 60000 Unicode code points, counting only file content (not line numbers, metadata, or line separators), so a long line is returned exactly and never cut to fit a fixed width.
- `column_offset` (default 1) selects the start column of the first returned line only. It does not require `limit: 1` and does not apply to later lines, which start at column 1. A column past the end of the first line is an error; on an empty line the valid endpoint is column 1.
- When the result is truncated, the numbered body contains only exact file content (never synthetic truncation markers). The header reports whether the line count (`line_limit`) or the 60000 code point budget (`character_limit`) stopped the read, plus `next`, the first position that was not returned. A `[TRUNCATED: ...]` line after the body repeats that position; it contains no call instructions.
- To continue, call `read` again with that next position (`offset` is its line, and `column_offset` is its column when it is not 1).
- Use `read` on directories instead of `bash ls`. Directories keep their own listing format, also paged by `offset`/`limit`.
- `path` may be a `kkstudio:` resource URI or a local path in the selected Environment.
- Use `workdir` only to resolve a relative local path. It must be an expanded absolute directory on the target daemon's file system; no call inherits a previous directory, session default, cwd, or home directory.
- Omit `workdir` for `kkstudio:` URIs and absolute local paths.

Examples:
- `read({ path: "src/example.ts", workdir: "/srv/project/packages/web" })`
- `read({ path: "src/example.ts", workdir: "/srv/project/services/api", offset: 120, limit: 40 })`
- `read({ path: "src/bundle.js", workdir: "/srv/project", offset: 1, column_offset: 60001 })`
- `read({ path: "kkstudio:/skills/review/checklist/SKILL.md" })`
- `read({ path: "/var/log/app.log", offset: 120, limit: 40 })`
- `read({ path: ".", workdir: "/srv/project" })`
- `read({ path: "src/", workdir: "C:/src/project" })`
- `read({ path: "screenshot.png", workdir: "/tmp/agent-artifacts" })`
