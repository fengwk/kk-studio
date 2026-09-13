/**
 * Project Coordinator 与 IssueRun 的 Harness Session 引导服务。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>按 Project -> Issue -> IssueRun 严格锁序加载权威锁定的 Owner 行；
 *   <li>物化权威 Agent 的 BranchSettings 并构造初始 USER 命令；
 *   <li>委托 {@link fun.fengwk.kkstudio.platform.orchestration.HarnessCommandAcceptanceOrchestrator}
 *       在同一物理事务内原子完成 Session、ROOT Entry、初始 Thread、Command、Work 与归属关系写入；
 *   <li>支持同 Owner 的精确幂等重放，确定性拒绝跨 Owner 归属冲突与不一致重放。
 * </ul>
 *
 * <p>架构边界：
 *
 * <ul>
 *   <li>Stage 2 仅负责 Session 及其关联关系边的原子引导与精确重放，不负责创建 IssueRun 本身；
 *   <li>Stage 3 的 Issue Controller Reconciler 将在同一个 Spring 物理事务中先创建 IssueRun， 再调用本服务引导 Harness
 *       Session。
 * </ul>
 */
package fun.fengwk.kkstudio.platform.project.session;
