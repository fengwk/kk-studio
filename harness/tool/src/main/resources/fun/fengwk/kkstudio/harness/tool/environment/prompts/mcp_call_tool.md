调用本地配置的 MCP server 上的一个工具（可能有副作用，非幂等）。

用法：
- `server` 必须精确匹配某个已配置且启动成功的 MCP server 名称；未知或启动失败的 server 是确定性错误。
- `tool` 必须精确匹配该 server 通过 `mcp_list_tools` 暴露的工具名称；未知工具是确定性错误。
- `arguments` 是任意 JSON 对象，原样传递给 MCP 工具；不要把 JSON 包成字符串。
- 上游 `isError` 结果会作为模型可见的错误 ToolResult 返回；文本与结构化 JSON 结果原样保留。

参数：
- `server`（必填）：MCP server 名称。
- `tool`（必填）：MCP 工具名称。
- `arguments`（必填）：任意 JSON 对象参数。
