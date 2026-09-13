/**
 * Daemon 本地 stdio MCP 执行基础：严格解析、代际管理与能力实现。
 *
 * <p>本包把 Platform 下发的冻结本地 MCP 配置转成受管子进程调用：{@link
 * fun.fengwk.kkstudio.harness.daemon.mcp.DaemonLocalMcpParser} 只接受 canonical UUID identity 与 {@code
 * type/environmentId/command/cwd} 必填字段（{@code env}/{@code enabled}/{@code timeoutMillis}
 * 可选并带默认值），整值 {@code ${VAR}} 从 {@code System.getenv} 解析； {@link
 * fun.fengwk.kkstudio.harness.daemon.mcp.DaemonLocalMcpManager} 按 {@code (serverId, configVersion)}
 * 懒共享子进程，新版本 fencing 旧版本并在活动调用归零后关闭，同一版本出现不同配置时 fail closed。
 *
 * <p>能力侧只暴露两个固定身份：{@code mcp.local.call}（模型可见）与 {@code mcp.local.discover}（管理专用，返回 {@code
 * {serverId,configVersion,tools[]}} envelope）。两者都不接受 {@code workdir}，都使用单一绝对
 * deadline（请求预算与配置超时的较小值）同时覆盖 lazy 初始化与执行，并在失败时返回固定不透明文本。
 */
package fun.fengwk.kkstudio.harness.daemon.mcp;
