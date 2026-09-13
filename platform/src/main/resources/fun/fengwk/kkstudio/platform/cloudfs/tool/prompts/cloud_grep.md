# cloud_grep

Search file contents in Cloud File System using RE2/J regular expressions or literal strings.

- Searches current revisions of text files in user directories.
- Exact `/.artifacts/tool-results/{threadId}/{invocationId}.txt` paths can also be searched.
- Early stops when `limit + 1` matches are found.
- Returns `No matches found` when nothing matches.
