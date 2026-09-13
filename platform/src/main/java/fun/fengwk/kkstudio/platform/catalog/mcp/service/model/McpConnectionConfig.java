package fun.fengwk.kkstudio.platform.catalog.mcp.service.model;

/** MCP Server 传输配置标记接口。 */
public sealed interface McpConnectionConfig permits RemoteConnectionConfig, LocalConnectionConfig {}
