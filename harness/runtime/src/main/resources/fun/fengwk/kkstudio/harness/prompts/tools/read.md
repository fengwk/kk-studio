Read a text file, directory, or supported media resource by path.

Usage:
- Use `read` before editing an existing text file.
- For text files, the result starts with a small header, then a blank line, then numbered lines in `number|content` form.
- Header order: `path`, optional `encoding`, optional `ends_with_newline`, then `range`. `ends_with_newline` appears only when the read reaches EOF; if a filled window has more content after it, the field is omitted and reading stops. When the result is truncated it also carries `truncated: yes`, `truncation_reason`, and `next`; otherwise those three fields are omitted.
- `encoding` appears only when the file is not plain UTF-8: a BOM-marked or declared encoding such as `UTF-16LE` or `GBK`. `read` decodes that text for you; a UTF-8 BOM is not reported.
- `read` recognizes a byte order mark and the standard encoding declarations of XML, CSS, HTML, Python, Ruby and JSP files, in that order. A file that is neither valid UTF-8 nor BOM-marked nor declared is reported as not UTF-8; convert it to UTF-8 or add a standard declaration instead of guessing.
- `range` uses 1-based positions `start_line:start_column-end_line:end_column`. `range: empty` means the requested start is past the end of the file (this also covers an empty file), so there is no body.
- `range` describes positions in the whole file. When present, `ends_with_newline` describes whether the whole file ends with a newline.
- Only the text after the first `|` on a numbered line is file content; the header lines and number column are not part of the file.
- `ends_with_newline: yes` means the file ends with a newline, even though `read` does not add an extra numbered blank line to represent it.
- `offset` (default 1) selects the first returned line; `limit` (default and maximum 2000) bounds the number of returned lines. The body is additionally bounded to 60000 Unicode code points, counting only file content (not line numbers, metadata, or line separators), so a long line is returned exactly and never cut to fit a fixed width.
- `column_offset` (default 1) selects the start column of the first returned line only. It does not require `limit: 1` and does not apply to later lines, which start at column 1. A column past the end of the first line is an error; on an empty line the valid endpoint is column 1.
- When the result is truncated, the numbered body contains only exact file content (never synthetic truncation markers). The header reports whether the line count (`line_limit`) or the 60000 code point budget (`character_limit`) stopped the read, plus `next`, the first position that was not returned. A `[TRUNCATED: ...]` line after the body repeats that position; it contains no call instructions.
- To continue, call `read` again with that next position (`offset` is its line, and `column_offset` is its column when it is not 1).
- `read` treats a file as binary when its first 8000 bytes contain a NUL byte (for BOM-marked UTF-16 and UTF-32, their decoded characters). Other control characters are text.
- Use `read` on directories instead of `bash ls`. Directories keep their own listing format, also paged by `offset`/`limit`.
- `path` is an absolute path on the selected Environment's file system, or a `resource://` URI:
  - `resource://files/<uuid>`: a shared resource. Read it with `read`; `offset`/`limit` apply only when it is returned as a text window. A supported media resource (image, audio, video, or PDF) may be returned as actual media content when the current model and provider protocol allow it; otherwise the call fails explicitly.
  - `resource://skills/<package>/<version>/<skill>/<relativePath>`: a file from a published skill package. The URI is read by the platform and held by the current Session; percent-encode path segments when needed.
- Local paths require an Environment. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory. On Windows targets use a drive-rooted or UNC path such as `C:/src/project` or `//server/share/project`.

Examples:
- `read({ path: "/srv/project/packages/web/src/example.ts" })`
- `read({ path: "/srv/project/services/api/src/example.ts", offset: 120, limit: 40 })`
- `read({ path: "/srv/project/src/bundle.js", offset: 1, column_offset: 60001 })`
- `read({ path: "resource://files/1f2e3d4c-5b6a-4c8d-9e0f-1a2b3c4d5e6f", offset: 1, limit: 2000 })`
- `read({ path: "/var/log/app.log", offset: 120, limit: 40 })`
- `read({ path: "/srv/project" })`
- `read({ path: "C:/src/project/src/" })`
- `read({ path: "/tmp/agent-artifacts/screenshot.png" })`
