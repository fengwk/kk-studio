/**
 * Project Issue Run 的显式编排组件：冻结上下文投影与交接工具。
 *
 * <p>本包承担两件事：
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.platform.project.tool.ProjectRunContextProjector} 从本 branch 冻结的
 *       {@code project/run} contributor state 投影 Issue Agent 上下文；DatabaseTurnResolver 不再反查 Thread
 *       属于哪个 Issue。
 *   <li>{@link fun.fengwk.kkstudio.platform.project.tool.IssueTransitionTool} 是本包注册的 SELECTABLE
 *       工具，也是 Issue Agent 唯一的业务写入口：它只把交接目标写入 {@code project_issue_run.next_state}，绝不直接改变 Issue 状态。
 * </ul>
 *
 * <p>边界约定：业务状态机与事务锁序由 {@code fun.fengwk.kkstudio.project.service} / {@code
 * fun.fengwk.kkstudio.project.repo} 拥有；本包不复制 workflow 校验规则（复用领域 {@code ProjectWorkflowJsonCodec} 与
 * {@code IssueStateTransitions}），不写 Issue 活动、 不跨模块访问其他域的
 * Mapper，也不提供任何"缺少事实就静默降级"的便利路径——归属或快照不可用时一律失败关闭。
 */
package fun.fengwk.kkstudio.platform.project.tool;
