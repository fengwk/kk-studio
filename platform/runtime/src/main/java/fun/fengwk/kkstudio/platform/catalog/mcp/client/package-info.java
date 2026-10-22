/**
 * MCP 客户端基础能力。
 *
 * <p>提供 Streamable HTTP 传输、严格配置与结果模型、单一截止时间、调用级取消及生命周期收敛。公共边界不暴露底层 LangChain4j
 * 类型，也不在异常中保留连接配置或协议载荷。
 *
 * <p>Jackson 2 只出现在 SDK 适配边界 {@link
 * fun.fengwk.kkstudio.platform.catalog.mcp.client.McpResultExtractor}：SDK 的 {@code
 * McpToolResultExtractor} 签名固定接收 {@code com.fasterxml.jackson.databind.JsonNode}。该边界之外的生产代码 只使用
 * Jackson 3（{@code tools.jackson}），模块自有 JSON（工具 schema、调用详情）也都由 Jackson 3 写出。
 */
package fun.fengwk.kkstudio.platform.catalog.mcp.client;
