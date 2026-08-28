Call one tool on a locally configured MCP server (may have side effects; non-idempotent).

Usage:
- `server` must exactly match a configured MCP server name that started successfully; an unknown or failed server is a deterministic error.
- `tool` must exactly match a tool name exposed by that server through `mcp_list_tools`; an unknown tool is a deterministic error.
- `arguments` is an arbitrary JSON object passed through to the MCP tool as-is; do not wrap the JSON in a string.
- An upstream `isError` result is returned as a model-visible error ToolResult; text and structured JSON results are preserved as-is.

Arguments:
- `server` (required): MCP server name.
- `tool` (required): MCP tool name.
- `arguments` (required): arbitrary JSON object arguments.
