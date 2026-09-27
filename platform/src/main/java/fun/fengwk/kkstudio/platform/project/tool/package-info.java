/**
 * Project Issue Agent 的受控 Turn 事实与交接工具。
 *
 * <p>本包只承担两件事：
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.platform.project.tool.ProjectIssueTurnResolver} 把 Harness Thread
 *       解析为唯一的 Issue+Agent 稳定归属，并投影当前 Issue、Project workflow 阶段与活动 Run 的权威事实；DatabaseTurnResolver
 *       只消费该投影，不再自行推断角色。
 *   <li>{@link fun.fengwk.kkstudio.platform.project.tool.IssueTransitionTool} 是本包唯一注册的工具，也是 Issue
 *       Agent Thread 唯一的业务写入口：它只把交接目标写入 {@code project_issue_run.next_state}，绝不直接改变 Issue 状态。
 * </ul>
 *
 * <p>边界约定：业务状态机与事务锁序由 {@code fun.fengwk.kkstudio.project.service} / {@code
 * fun.fengwk.kkstudio.project.repo} 拥有；本包不复制 workflow 校验规则（复用领域 {@code ProjectWorkflowJsonCodec} 与
 * {@code IssueStateTransitions}），不写 Issue 活动、 不跨模块访问其他域的
 * Mapper，也不提供任何"缺少事实就静默降级"的便利路径——归属存在但事实不可用时一律失败关闭。
 */
package fun.fengwk.kkstudio.platform.project.tool;
