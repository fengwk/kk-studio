Apply one or more text-file changes as a single validated patch.

Supply the complete protocol text as `patchText`; do not wrap it in Markdown fences.
The daemon validates every operation and context before changing any file. If
preflight fails, no file is changed.

The protocol is:

*** Begin Patch
*** Workdir: <relative directory>
*** Add File: <relative path>
+first line
+second line
*** Update File: <relative path>
@@ optional unique anchor
 unchanged context
-line to remove
+line to add
*** Delete File: <relative path>
*** End Patch

`*** Workdir:` is optional and must appear immediately after `*** Begin Patch`.
All paths are relative to the daemon invocation workspace; absolute paths,
parent (`..`) segments, and paths that resolve through a symlink outside that
workspace are rejected. Omit the Add File body to create an empty file.

An Update File must contain one or more `@@` hunks. Context lines begin with a
space, removed lines with `-`, and added lines with `+`. Every hunk must contain
at least one such line. The optional anchor and the complete context/removal
sequence must match exactly one location. Use `*** End of File` after the final
hunk when the matched sequence must end at the file end. Move directives are
not supported.

Only regular UTF-8 text files can be updated or deleted. Existing line-ending
style and final-newline state are preserved. Each path may occur only once.
The result is a concise summary of every changed file.
