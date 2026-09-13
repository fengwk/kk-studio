# cloud_read

Read the content of a file or directory in Cloud File System.

- When reading a directory, returns immediate children sorted by name. Directories end with `/`.
- When reading a text file, returns path, kind, revision, whether it ends with newline, and 1-based numbered lines.
- When reading a blob, decodes UTF-8 text if applicable, or returns metadata and binary resource.
- Artifact results under `/.artifacts/tool-results/` can only be read using exact file paths.
