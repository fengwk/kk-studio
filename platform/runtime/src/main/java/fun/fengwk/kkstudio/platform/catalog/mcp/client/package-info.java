/**
 * MCP 客户端基础能力。
 *
 * <p>提供 Streamable HTTP 传输、严格配置与结果模型、单一截止时间、调用级取消及生命周期收敛。{@link McpClient} 及其配置、工具定义与结果模型不暴露官方 MCP
 * Java SDK 类型；{@link McpResultExtractor} 是 SDK 结果的适配点，也不在异常中保留连接配置或协议载荷。
 *
 * <p>模块自有 JSON 与 SDK 内容序列化统一使用 Jackson 3（{@code tools.jackson}），不存在 Jackson 2 适配边界。
 */
package fun.fengwk.kkstudio.platform.catalog.mcp.client;
