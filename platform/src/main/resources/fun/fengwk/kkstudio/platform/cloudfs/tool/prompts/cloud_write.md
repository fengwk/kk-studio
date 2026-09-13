# cloud_write

Create a new text file or overwrite an existing text file with complete content in Cloud File System.

- `expected_revision` is required: use 0 to create a new file, or N (>0) to replace current revision N.
- When creating a new file, missing parent directories are created automatically.
- Modifications under `/.artifacts` are strictly forbidden.
