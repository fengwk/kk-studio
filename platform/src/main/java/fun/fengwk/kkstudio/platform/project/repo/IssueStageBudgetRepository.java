package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueStageBudgetRow;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue_stage_budget} 持久化端口：每 {@code (issueId, state)} 一份额度。
 *
 * <p>额度不含 Agent/Thread/Session，按领域 {@code IssueStageBudget} 的高水位语义计数；修改在 Issue 锁下进行，没有独立版本。
 */
public interface IssueStageBudgetRepository {

  IssueStageBudgetRow get(UUID issueId, String state);

  boolean insert(IssueStageBudgetRow budget);

  /** 更新额度上限与重置高水位。 */
  boolean update(IssueStageBudgetRow budget);

  boolean delete(UUID issueId, String state);

  List<IssueStageBudgetRow> listByIssueId(UUID issueId);
}
