List locally configured MCP servers and their tools (read-only; never invokes any remote tool).

Usage:
- Without `server`, returns the status and tools of all configured servers;
- With `server`, returns only that server's status and tools; an unknown name is a deterministic error.
- Each tool of a READY server carries name, description, and its full input schema;
  a FAILED server carries only a bounded error summary, never headers, environment, commands, URLs, or local paths.

Arguments:
- `server` (optional): MCP server name.
