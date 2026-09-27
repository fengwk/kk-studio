package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.PauseReason;

import java.util.List;
import java.util.UUID;

/**
 * Issue 用例：业务状态、控制门禁与阶段额度的唯一写入口。
 *
 * <p>业务状态只由明确操作流转，绝不从模型回复猜测；BLOCKED 记录恢复目标与原因，控制暂停与业务 BLOCKED 分开；阶段额度按 {@code (issue, state)}
 * 授权并只能由有权限的人在无活动 Run 时重置。所有写操作在 Project SHARE → Issue UPDATE 锁序下以版本 CAS 提交。
 *
 * <p>每个写操作都要求调用方提供请求键 {@code requestKey}，并以请求键与规范化请求指纹去重（设计 §4.6）：同键同指纹是丢响应后的精确重试，
 * 直接返回原结果而不重复应用，且判定先于版本校验；同键异指纹是请求键复用，返回确定性冲突。因此重试不会重复改变状态、门禁或额度。
 */
public interface IssueService {

  Issue createIssue(UUID projectId, String title, String description);

  Issue getIssue(UUID issueId);

  List<Issue> listIssues(UUID projectId, boolean archived);

  /** 业务阻塞：原子记录 BLOCKED、原阶段与原因。 */
  Issue blockIssue(UUID issueId, long expectedVersion, String requestKey, String reason);

  /** 恢复业务阻塞：显式回到阻塞前阶段。 */
  Issue recoverIssue(UUID issueId, long expectedVersion, String requestKey);

  /** 控制暂停：阻止新派发并记录原因与详情（USER/ERROR/UNKNOWN）。 */
  Issue pauseIssue(
      UUID issueId, long expectedVersion, String requestKey, PauseReason reason, String detail);

  /** 显式解除控制暂停门禁。 */
  Issue resumeIssue(UUID issueId, long expectedVersion, String requestKey);

  /** 正常转移：目标是当前阶段 workflow {@code next} 白名单成员且处于启用状态。 */
  Issue transition(UUID issueId, long expectedVersion, String requestKey, String toState);

  /** DONE 重开：显式回到 INIT。 */
  Issue reopen(UUID issueId, long expectedVersion, String requestKey);

  /** 首次自动执行接受时授权阶段额度（工作阶段必须是启用且有 Agent 的阶段）。 */
  StageBudgetView authorizeStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns);

  /** 重置阶段额度：高水位取服务端当前 {@code nextRunOrdinal - 1}，不得回退而重新授权历史 Run。 */
  StageBudgetView resetStageBudget(
      UUID issueId, long expectedVersion, String requestKey, String state, int maxRuns);

  StageBudgetView getStageBudget(UUID issueId, String state);

  /** 阶段额度视图：从 Run 历史实时计算的已消耗与剩余次数。 */
  record StageBudgetView(
      String state, int maxRuns, long budgetAfterOrdinal, long usedRuns, long remainingRuns) {}
}
