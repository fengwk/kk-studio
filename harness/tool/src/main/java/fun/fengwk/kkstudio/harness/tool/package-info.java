/**
 * Tool 描述、稳定身份、参数 schema、调用/结果内容与 JSON codec 公共契约。
 *
 * <p>{@link AgentToolId} 固定持久化稳定身份，{@link ToolDescriptor} 表达 route-neutral 的功能描述， {@link
 * AgentToolDefinition} 组合身份与描述，{@link ToolCall} 经 {@code validateFor} 执行归一化与校验， {@link ToolResult}
 * 强制执行 detailsJson 1 MiB 与最多 64 个 contents 项边界。
 *
 * <p>本包不依赖 Environment、Contributor、Session、Runtime、Spring 或持久化框架； 工具执行 SPI、权限、调度和 durable
 * 状态由上层模块负责。
 */
package fun.fengwk.kkstudio.harness.tool;
