Create a text file or intentionally overwrite a whole text file.

Usage:
- Use `write` for new files or intentional whole-file replacement only.
- For existing files, prefer `edit`; use `write` only for whole-file replacement or roughly 60%+ file-wide rewrites, never for localized edits that an `edit` can handle safely.
- Provide complete content without placeholders such as `...` or omitted sections.
- `content` replaces the file exactly as provided: line endings are never rewritten, and `write`
  never restores an older newline style. When overwriting an existing text file, its encoding and
  BOM are preserved, and content that cannot be represented in that encoding is rejected.
- `write` supports regular text files only. Directories, devices, FIFOs and binary files are
  rejected before any write.
- `write` returns a success message only. If you need to inspect the resulting file content, use `read` afterward.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `write({ path: "/srv/project/src/new-module.ts", content: "export const demo = 1;\n" })`
- `write({ path: "/srv/project/packages/web/docs/new-template.md", content: "# New template\n\nComplete file content.\n" })`
- `write({ path: "/tmp/agent-artifacts/generated/report.txt", content: "full generated report\n" })`
