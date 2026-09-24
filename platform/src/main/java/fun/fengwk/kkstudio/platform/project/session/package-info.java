/**
 * Issue Agent Session 的 Harness Session 引导服务。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>按 Project -&gt; Issue -&gt; IssueAgentSession 严格锁序加载权威锁定的 Owner 行；
 *   <li>物化权威 Agent 的 BranchSettings 并构造初始 USER 命令；
 *   <li>委托 {@link fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator}
 *       在同一物理事务内原子完成 Session、ROOT Entry、初始 Thread、Command、Work 与归属关系写入；
 *   <li>支持同一 {@code (issueId, agentName)} 归属的精确幂等重放，确定性拒绝跨归属冲突与不一致重放。
 * </ul>
 *
 * <p>架构边界：本包只负责稳定归属与其 Session 的原子引导和精确重放，不负责创建 IssueRun；当前 Run 由 {@link
 * fun.fengwk.kkstudio.platform.project.controller.IssueReconciler} 在同一 Spring 物理事务中创建后，再调用本服务复用或引导
 * 对应归属的 Harness Session。
 */
package fun.fengwk.kkstudio.platform.project.session;
