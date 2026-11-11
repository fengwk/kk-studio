/**
 * Project Issue Run 的交接工具。
 *
 * <p>本包承担一件事：{@link fun.fengwk.kkstudio.project.tool.IssueTransitionTool} 是本包注册的 SELECTABLE 工具，也是
 * Issue Agent 唯一的业务写入口：它只把交接目标写入 {@code project_issue_run.next_state}，绝不直接改变 Issue 状态。
 *
 * <p>边界约定：业务状态机与事务锁序由 {@code fun.fengwk.kkstudio.project.service} / {@code
 * fun.fengwk.kkstudio.project.repo} 拥有；本包不复制 workflow 校验规则（复用领域 {@code ProjectWorkflowJsonCodec} 与
 * {@code IssueStateTransitions}），不写 Issue 活动、 不跨模块访问其他域的
 * Mapper，也不提供任何"缺少事实就静默降级"的便利路径——归属或调用位置不可用时一律失败关闭。Issue 的运行上下文只由接受 Run 时的 Markdown 任务消息承载。
 */
package fun.fengwk.kkstudio.project.tool;
