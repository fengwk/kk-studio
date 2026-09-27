package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.PauseReason;

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

  /** 编辑 Issue 需求事实（标题与描述）；当前阶段与执行事实不由本操作改变。 */
  Issue updateIssue(UUID issueId, long expectedVersion, String title, String description);

  /** 归档：要求没有活动 Run；归档后禁止执行，不删除历史与资源。 */
  Issue archiveIssue(UUID issueId, long expectedVersion);

  /** 恢复归档：只回到可编辑状态，不自动新建 Run。 */
  Issue unarchiveIssue(UUID issueId, long expectedVersion);

  /** 追加普通评论：只留痕，不自动唤醒 Agent。 */
  Issue appendComment(UUID issueId, long expectedVersion, String requestKey, String body);

  /** 追加定向指示：必须投递给明确的当前 Run；没有活动 Run 时先明确启动或恢复，绝不构造隐藏的未来阶段队列。 */
  Issue appendInstruction(UUID issueId, long expectedVersion, String requestKey, String body);

  /** 读取 Issue 事实流的有界窗口，按序号升序。 */
  List<IssueActivity> listActivities(UUID issueId, long afterSequence, int limit);

  /** 读取 Issue 的稳定 Agent Thread 归属；绑定不可重绑，因此只需返回事实。 */
  List<IssueAgentThread> listAgentThreads(UUID issueId);

  /** 业务阻塞：原子记录 BLOCKED、原阶段与原因。 */
  Issue blockIssue(UUID issueId, long expectedVersion, String requestKey, String reason);

  /** 恢复业务阻塞：显式回到阻塞前阶段。 */
  Issue recoverIssue(UUID issueId, long expectedVersion, String requestKey);

  /** 控制暂停：阻止新派发并记录原因与详情（USER/ERROR/UNKNOWN）。 */
  Issue pauseIssue(
      UUID issueId, long expectedVersion, String requestKey, PauseReason reason, String detail);

  /** 显式解除控制暂停门禁。 */
  Issue resumeIssue(UUID issueId, long expectedVersion, String requestKey);

  /** 人工终止 Issue 执行：活动 Run 收尾为 CANCELLED 并保留 USER 暂停门禁；在途副作用不明则进入 UNKNOWN。 */
  Issue stopIssue(UUID issueId, long expectedVersion, String requestKey, String detail);

  /** 记录人工核查依据并解除 UNKNOWN 门禁（转为 USER 暂停门禁，需显式 resumeIssue 唤醒）。 */
  Issue resolveUnknown(UUID issueId, long expectedVersion, String requestKey, String verification);

  /** 深删除 Issue：严格要求无活动 Run 且无未核查的 UNKNOWN，按依赖顺序清理各表与 Harness Session。 */
  void deleteIssue(UUID issueId, long expectedVersion);

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
