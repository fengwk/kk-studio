Perform exact text replacements in one text file. One call can carry any number of independent replacements.

Usage:
- Prefer using `read` before editing an existing text file so you can copy the exact current text.
- `read` text output starts with a small header (`path`, `encoding` only when the file is not plain UTF-8, `ends_with_newline` only when the read reaches EOF, and `range`), then a blank line, then numbered body lines in `number|content` form.
- When copying text from `read`, use only the file content after the first `|` on each numbered line. Never include the number column or the `|` itself in `old_string` or `new_string`.
- For long lines, `read` returns exact bounded fragments without truncation markers inside the body. Copy the unique fragment directly from the numbered body into `old_string`. To inspect subsequent parts of a long line, pass `column_offset` to `read`.
- If `read` reports `ends_with_newline: yes`, remember that the file ends with a newline, even though `read` does not add an extra numbered blank line to represent it.
- Prefer `edit` for existing text files. Use `write` for new files or intentional whole-file replacement.
- `edit` keeps the file's encoding, BOM and existing line-ending style. Matching happens on a
  newline-normalized view, so CRLF/CR/LF spelling alone is not a change. An edit that would
  change the effective encoding is rejected: keep the file's declaration as it is, or use `write`
  with the complete content to convert the file.
- `edit` supports regular text files only. Directories, devices, FIFOs and binary files are
  rejected before any write.
- Put all independent changes to one file into one call, one `edits` entry per change. Every entry matches the original file content, not the result of earlier entries, and entries must not overlap.
- An entry fails if `old_string` is not found, or is found more than once without `replace_all`. Add surrounding context to make it unique, or set `replace_all` to change every occurrence, for example to rename an identifier.
- If any entry fails, nothing is written and the error lists every failing entry with hints, such as text that matches when whitespace is ignored or the lines of duplicate matches. Fix those entries and send the whole call again.
- Separate `edit` and `write` calls on the same file run in order; each starts from the file left by the previous call, and a later failure does not undo an earlier success. Issue a change that depends on another call's result only after that call has returned.
- `path` must be an absolute path on the target Environment's file system. A relative path is rejected; no call inherits a previous directory, session default, cwd, or home directory. On Windows targets use a drive-rooted or UNC path such as `C:/src/project`.

Examples:
- `edit({ path: "/srv/project/src/app.ts", edits: [{ old_string: "const port = 80", new_string: "const port = 8080" }] })`
- `edit({ path: "/srv/project/src/app.ts", edits: [{ old_string: "oldName", new_string: "newName", replace_all: true }, { old_string: "// TODO: remove\n", new_string: "" }] })`
