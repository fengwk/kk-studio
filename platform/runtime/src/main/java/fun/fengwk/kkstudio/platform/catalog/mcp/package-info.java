/**
 * Platform MCP 核心切片：name-keyed 持久配置 CRUD/CAS、事务外网络发现 + 事务内锁/CAS 的原子替换、 模型可见工具名身份与动态 {@code
 * McpToolCatalog}。
 *
 * <p>仅支持 Streamable HTTP 传输；全部工具 NON_IDEMPOTENT；per-call MCP client（绝不缓存）；错误对外只暴露 稳定通用文本，绝不泄漏 URL /
 * token / header。动态 {@code McpToolCatalog} 通过 {@code CompositeRuntimeToolCatalog} 聚合入统一运行时目录。
 */
package fun.fengwk.kkstudio.platform.catalog.mcp;
