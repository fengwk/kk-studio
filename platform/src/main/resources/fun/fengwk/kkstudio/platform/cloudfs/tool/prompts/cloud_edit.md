# cloud_edit

Perform exact string replacement in a text file in Cloud File System.

- `expected_revision` must match the current revision (>0).
- `old_string` must be non-empty and must match exactly once unless `replace_all` is true.
- `new_string` can be empty to delete matched text.
- Returns a bounded, line-numbered contextual diff upon success.
- Modifications under `/.artifacts` are strictly forbidden.
