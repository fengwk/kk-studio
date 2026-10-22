package fun.fengwk.kkstudio.project.service;

import fun.fengwk.kkstudio.project.model.IssueRun;

import java.util.List;
import java.util.UUID;

/**
 * Issue Run 用例：原子接受一次执行，以及终态/等待收尾。
 *
 * <p>接受在同一物理事务完成权限与当前状态检查、阶段预算检查、Agent Thread 创建或选择、起点冻结、Run 与唯一 RUN 活动插入、Harness 初始命令接受及 Work
 * 登记；模型/工具外部调用发生在事务外。终态收尾复验 Run 身份/版本/合法边/门禁，并与 Issue 状态和暂停门禁同事务写入。
 *
 * <p>每个写操作都要求调用方请求键 {@code requestKey}，并以请求键与规范化请求指纹去重（设计 §4.6）：同键同指纹按原 RUN 活动精确重放并返回原 Run，不新建
 * Session/Thread/命令、不重新扣额度、不重复推进状态，判定先于版本、状态与额度校验；同键异指纹确定性冲突。WAITING/RUNNING
 * 的重复置位是无写操作的空重放，可安全用于丢响应后的重试。
 */
public interface IssueRunService {

  /** 接受一次 Run：锁序为 Project SHARE → Issue UPDATE → 阶段预算 → Harness Session/Thread → Work。 */
  IssueRun acceptRun(UUID issueId, String requestKey);

  IssueRun getRun(UUID runId);

  IssueRun getActiveRun(UUID issueId);

  IssueRun getLatestRun(UUID issueId);

  List<IssueRun> listRuns(UUID issueId);

  /** 到安全点后暂停活动计时（WAITING）；额度限制新建 Run，不限制已接受 Run 的恢复。 */
  IssueRun waitRun(UUID runId, long expectedVersion);

  /** 从 WAITING 恢复活动计时（RUNNING），不消耗新的阶段额度。 */
  IssueRun resumeRun(UUID runId, long expectedVersion);

  /** 正常收尾：冻结历史区间与报告，提交可选交接目标到 Issue.state。 */
  IssueRun completeRun(
      UUID runId,
      long expectedVersion,
      String requestKey,
      UUID endEntryId,
      UUID finalAnswerEntryId,
      String nextState);

  /** 人工停止：收尾为 CANCELLED 并保留 USER 暂停门禁。 */
  IssueRun cancelRun(UUID runId, long expectedVersion, String requestKey, UUID endEntryId);

  /** 执行失败：收尾为 FAILED 并与 ERROR 暂停门禁同事务写入。 */
  IssueRun failRun(
      UUID runId, long expectedVersion, String requestKey, UUID endEntryId, String error);

  /** 在途副作用不明：收尾为 UNKNOWN 并与 UNKNOWN 暂停门禁同事务写入。 */
  IssueRun markUnknown(
      UUID runId, long expectedVersion, String requestKey, UUID endEntryId, String error);
}
