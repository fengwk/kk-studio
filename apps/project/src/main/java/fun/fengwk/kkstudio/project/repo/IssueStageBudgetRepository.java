package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.service.StageBudgetView;

import java.util.UUID;

/**
 * {@code project_issue_stage_budget} 持久化：每 {@code (issueId, state)} 一份额度。
 *
 * <p>额度不含 Agent/Thread/Session；修改在 Issue 锁下进行，没有独立版本。
 */
@Repository
public class IssueStageBudgetRepository {

  private static final RowMapper<StageBudgetView> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        Integer maxRuns = ProjectRows.nullableInteger(resultSet, "max_runs");
        Long budgetAfterOrdinal = ProjectRows.nullableLong(resultSet, "budget_after_ordinal");
        int max = maxRuns != null ? maxRuns : 0;
        long used = resultSet.getLong("used_runs");
        return new StageBudgetView(
            resultSet.getString("state"),
            max,
            budgetAfterOrdinal != null ? budgetAfterOrdinal : 0L,
            used,
            Math.max(0L, max - used));
      };

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public IssueStageBudgetRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  public StageBudgetView get(UUID issueId, String state) {
    return jdbc.sql(
            "select b.state, b.max_runs, b.budget_after_ordinal,"
                + " (select count(*) from project_issue_run r"
                + " where r.issue_id = b.issue_id and r.state = b.state"
                + " and r.ordinal > b.budget_after_ordinal) as used_runs"
                + " from project_issue_stage_budget b"
                + " where b.issue_id = :issueId and b.state = :state")
        .param("issueId", issueId)
        .param("state", state)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public boolean insert(UUID issueId, StageBudgetView budget) {
    boolean changed =
        jdbc.sql(
                    "insert into project_issue_stage_budget ("
                        + "issue_id, state, max_runs, budget_after_ordinal, created_at, updated_at"
                        + ") values ("
                        + ":issueId, :state, :maxRuns, :budgetAfterOrdinal,"
                        + " clock_timestamp(), clock_timestamp())")
                .param("issueId", issueId)
                .param("state", budget.state())
                .param("maxRuns", budget.maxRuns())
                .param("budgetAfterOrdinal", budget.budgetAfterOrdinal())
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  /** 更新额度上限与重置高水位。 */
  public boolean update(UUID issueId, StageBudgetView budget) {
    boolean changed =
        jdbc.sql(
                    "update project_issue_stage_budget"
                        + " set max_runs = :maxRuns,"
                        + " budget_after_ordinal = :budgetAfterOrdinal,"
                        + " updated_at = clock_timestamp()"
                        + " where issue_id = :issueId and state = :state")
                .param("maxRuns", budget.maxRuns())
                .param("budgetAfterOrdinal", budget.budgetAfterOrdinal())
                .param("issueId", issueId)
                .param("state", budget.state())
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }

  public int deleteByIssueId(UUID issueId) {
    int changed =
        jdbc.sql("delete from project_issue_stage_budget where issue_id = :issueId")
            .param("issueId", issueId)
            .update();
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }
}
