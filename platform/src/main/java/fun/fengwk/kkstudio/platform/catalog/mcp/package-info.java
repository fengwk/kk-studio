/**
 * Platform MCP 核心切片：持久配置 CRUD/CAS、事务外发现 + 事务内锁/CAS 的严格保存流程、 稳定工具身份（AgentToolId / ContributionId）与动态
 * {@code McpToolCatalog}。
 *
 * <p>仅支持 Streamable HTTP 传输；全部工具 NON_IDEMPOTENT；per-call MCP client（绝不缓存）；错误对外只暴露 稳定通用文本，绝不泄漏 URL /
 * token / header。集成 wiring 由后续切片完成。
 */
package fun.fengwk.kkstudio.platform.catalog.mcp;
