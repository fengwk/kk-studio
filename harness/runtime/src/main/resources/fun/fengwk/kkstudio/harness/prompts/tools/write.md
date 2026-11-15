Create a text file or intentionally overwrite a whole text file.

Usage:
- Use `write` for new files or intentional whole-file replacement only.
- For existing files, prefer `edit`; use `write` only for whole-file replacement or roughly 60%+ file-wide rewrites, never for localized edits that an `edit` can handle safely.
- Provide complete content without placeholders such as `...` or omitted sections.
- `content` replaces the file exactly as provided: line endings are never rewritten, and `write`
  never restores an older newline style.
- Encoding: content that starts with U+FEFF asks for a byte order mark; that character itself is
  never written as content. An existing file keeps its byte order mark and encoding. Without a byte
  order mark the standard declaration in `content`, detected after the U+FEFF, decides (for example
  `<meta charset>` or `# coding:`). A UTF-16 or UTF-32 declaration writes that encoding (a generic
  label uses little endian) and requires the leading U+FEFF; a legacy encoding must not have it; a
  UTF-8 declaration or no declaration writes UTF-8, with a BOM only when you asked for one. A
  declaration that contradicts an existing byte order mark is rejected, and so is dropping the
  declaration of an existing non-UTF-8 file: keep the declaration, or declare the new encoding
  explicitly to convert the file.
- Content that cannot be represented in the target encoding is rejected instead of substituted, and
  an existing file whose declaration the codec cannot use is never overwritten.
- When the original file could not be decoded losslessly and the saved text still contains U+FFFD
  (�), the result adds a `Note:` line because those original bytes are lost, and the approval shows
  the same warning above the diff.
- `write` supports regular text files only. Directories, devices, FIFOs and binary files are
  rejected before any write.
- `write` returns a success message only. If you need to inspect the resulting file content, use `read` afterward.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `write({ path: "/srv/project/src/new-module.ts", content: "export const demo = 1;\n" })`
- `write({ path: "/srv/project/packages/web/docs/new-template.md", content: "# New template\n\nComplete file content.\n" })`
- `write({ path: "/tmp/agent-artifacts/generated/report.txt", content: "full generated report\n" })`
