/**
 * Daemon 本地 MCP 能力：严格配置解析、server registry 与固定桥接工具。
 *
 * <p>MCP 配置/discovery 是纯 daemon 本地关注点，无服务端持久化或 CRUD。LangChain4j 类型只允许出现在 {@code langchain}
 * 子包适配器中；registry 与桥接工具只依赖自有端口/records。READY 只上报 name/status/有界错误/工具摘要， headers、environment、命令、URL
 * 与本地路径永不外报。
 */
package fun.fengwk.kkstudio.harness.daemon.mcp;
