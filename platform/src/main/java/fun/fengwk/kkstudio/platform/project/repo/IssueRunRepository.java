package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueRun;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue_run} 持久化端口。
 *
 * <p>同一 Issue 至多一个 RUNNING/WAITING 主 Run（部分唯一索引），终态收尾以 {@code version} CAS 推进。
 */
public interface IssueRunRepository {

  boolean insert(IssueRun run);

  IssueRun getById(UUID id);

  IssueRun lockById(UUID id);

  IssueRun getActiveByIssueId(UUID issueId);

  IssueRun lockActiveByIssueId(UUID issueId);

  IssueRun getLatestByIssueId(UUID issueId);

  List<IssueRun> listByIssueId(UUID issueId);

  /** 本 Issue 指定阶段中序号大于高水位的 Run 数（不论 Agent 与状态），用于阶段额度计算。 */
  long countByIssueIdAndStateAfterOrdinal(UUID issueId, String state, long afterOrdinal);

  boolean updateById(IssueRun run, long expectedVersion);

  boolean deleteById(UUID id, long expectedVersion);
}
