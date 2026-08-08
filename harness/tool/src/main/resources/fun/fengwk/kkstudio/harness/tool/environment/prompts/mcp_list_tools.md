列出本地配置的 MCP server 及其工具（只读，不调用任何远程工具）。

用法：
- 不传 `server` 时，返回全部配置 server 的状态与工具；
- 传入 `server` 时，只返回该 server 的状态与工具；未知名称是确定性错误。
- READY server 的每个工具携带 name、description 与完整输入 schema；
  FAILED server 只携带有界的错误摘要，绝不包含 headers、environment、命令、URL 或本地路径。

参数：
- `server`（可选）：MCP server 名称。
