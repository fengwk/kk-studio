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
- `workdir` is optional: pass it only to resolve a relative `path`. It must be an expanded
  absolute directory on the target daemon's file system; no call inherits a previous directory,
  session default, environment root, cwd, or home directory. An absolute `path` needs no
  `workdir`; a relative `path` without an explicit `workdir` is rejected.

Examples:
- `write({ path: "/srv/project/src/new-module.ts", content: "export const demo = 1;\n" })`
- `write({ path: "docs/new-template.md", workdir: "/srv/project/packages/web", content: "# New template\n\nComplete file content.\n" })`
- `write({ path: "generated/report.txt", workdir: "/tmp/agent-artifacts", content: "full generated report\n" })`
