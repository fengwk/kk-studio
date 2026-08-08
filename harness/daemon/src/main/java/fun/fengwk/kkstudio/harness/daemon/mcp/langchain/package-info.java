/**
 * LangChain4j MCP 适配器。
 *
 * <p>本包是唯一允许直接依赖 LangChain4j MCP 类型的 daemon 适配器：负责 transport/client 构建、初始化握手与工具调用映射。 LangChain4j
 * 类型不得越过 {@code McpServerClient} 端口进入 registry/桥接工具。
 */
package fun.fengwk.kkstudio.harness.daemon.mcp.langchain;
