/**
 * Tool 描述、参数 schema、调用/结果内容与 JSON codec 公共契约。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.tool.ToolDescriptor#name()} 是 Agent
 * 配置、权限、历史与运行时查询的唯一模型可见身份， {@link fun.fengwk.kkstudio.harness.tool.ToolDescriptor} 表达 route-neutral
 * 的功能描述， {@link fun.fengwk.kkstudio.harness.tool.AgentToolDefinition} 组合描述与可见性， {@link
 * fun.fengwk.kkstudio.harness.tool.ToolCall} 经 {@code validateFor} 执行归一化与校验， {@link
 * fun.fengwk.kkstudio.harness.tool.ToolResult} 强制执行 detailsJson 1 MiB 与最多 64 个 contents 项边界。
 *
 * <p>本包不依赖 Environment、Contributor、Session、Runtime、Spring 或持久化框架； 工具执行 SPI、权限、调度和 durable
 * 状态由上层模块负责。
 */
package fun.fengwk.kkstudio.harness.tool;
