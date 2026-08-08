/**
 * Environment Daemon 的独立进程边界。
 *
 * <p>Daemon 仅依赖 harness-tool 的 Tool SPI 和 wire 协议。它维护可重连连接与 invocation journal，但不将连接作为执行事实源，且不得反向依赖
 * Model、Agent 或 Runtime。每个连接 generation 的首个入站 sequence 建立基线；该 generation 内只接受紧邻的新 sequence 或最新
 * sequence 的重复， 重连后基线重置。
 *
 * <p>Environment 作用域使用 canonical {@code environmentName}（CLI {@code --environment-name}，规范 {@link
 * fun.fengwk.kkstudio.harness.tool.EnvironmentName}）；每个 envelope 只携带该逻辑路由名称并做单字段作用域校验。HELLO 声称该
 * 名称；若已被另一个 live daemon 持有，gateway 返回带 {@code ENVIRONMENT_NAME_CONFLICT} code 的 ERROR，daemon 停止重连并以
 * 非零状态退出。Skills 由 CLI {@code --skill-dir} 或默认 {@code ~/.agents/skills} 本地发现；READY 上报版本化能力对象 （skills
 * + MCP server 摘要），skill 正文经 {@code LOAD_SKILL} 按需加载，MCP 工具经固定桥接工具调用。
 */
package fun.fengwk.kkstudio.harness.daemon;
