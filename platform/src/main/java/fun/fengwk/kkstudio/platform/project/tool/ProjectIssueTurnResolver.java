package fun.fengwk.kkstudio.platform.project.tool;

import java.util.Optional;
import java.util.UUID;

/**
 * Harness Thread 到当前 Issue Agent turn 事实的解析端口。
 *
 * <p>三种结论必须严格区分，调用方不能把它们混成"没有角色工具"：
 *
 * <ul>
 *   <li>{@link Optional#empty()}：该 Thread 不属于任何 Issue+Agent 稳定归属，是普通 branch，不注入 Project 工具与上下文。
 *   <li>{@link ProjectIssueTurnRejection}：归属存在但当前不可执行（归属与 branch Agent 不匹配、阶段改派、Issue
 *       归档/暂停/不在工作阶段、无活动 Run 或活动 Run 属于别的 Thread/Session/阶段），必须失败关闭。
 *   <li>{@link IllegalStateException}：归属存在但 Issue/Project 行缺失等数据不一致，属于基础设施级错误，原样传播。
 * </ul>
 */
public interface ProjectIssueTurnResolver {

  /**
   * 解析该 Thread 在当前 branch 上的 Issue Agent 事实。
   *
   * @param threadId candidate path 的 Harness Thread 身份
   * @param agentName 该 branch 冻结的 Agent 自然名称，必须与稳定归属完全一致
   * @param sessionId 该 branch root 的 Session 身份，必须与活动 Run 冻结的 Session 一致
   */
  Optional<ProjectIssueTurnFacts> resolve(UUID threadId, String agentName, UUID sessionId);
}
