package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueStageBudgetRow;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue_stage_budget} 持久化：每 {@code (issueId, state)} 一份额度。
 *
 * <p>额度不含 Agent/Thread/Session，按领域 {@code IssueStageBudget} 的高水位语义计数；修改在 Issue 锁下进行，没有独立版本。
 */
@Repository
public class IssueStageBudgetRepository {

  private static final String COLUMNS = "issue_id, state, max_runs, budget_after_ordinal";

  private static final RowMapper<IssueStageBudgetRow> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        Integer maxRuns = ProjectRows.nullableInteger(resultSet, "max_runs");
        Long budgetAfterOrdinal = ProjectRows.nullableLong(resultSet, "budget_after_ordinal");
        return IssueStageBudgetRow.builder()
            .issueId(ProjectRows.uuid(resultSet, "issue_id"))
            .state(resultSet.getString("state"))
            .maxRuns(maxRuns != null ? maxRuns : 0)
            .budgetAfterOrdinal(budgetAfterOrdinal != null ? budgetAfterOrdinal : 0L)
            .build();
      };

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public IssueStageBudgetRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  public IssueStageBudgetRow get(UUID issueId, String state) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_stage_budget"
                + " where issue_id = :issueId and state = :state")
        .param("issueId", issueId)
        .param("state", state)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public boolean insert(IssueStageBudgetRow budget) {
    boolean changed =
        jdbc.sql(
                    "insert into project_issue_stage_budget ("
                        + "issue_id, state, max_runs, budget_after_ordinal, created_at, updated_at"
                        + ") values ("
                        + ":issueId, :state, :maxRuns, :budgetAfterOrdinal,"
                        + " clock_timestamp(), clock_timestamp())")
                .param("issueId", budget.getIssueId())
                .param("state", budget.getState())
                .param("maxRuns", budget.getMaxRuns())
                .param("budgetAfterOrdinal", budget.getBudgetAfterOrdinal())
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(budget.getIssueId());
    }
    return changed;
  }

  /** 更新额度上限与重置高水位。 */
  public boolean update(IssueStageBudgetRow budget) {
    boolean changed =
        jdbc.sql(
                    "update project_issue_stage_budget"
                        + " set max_runs = :maxRuns,"
                        + " budget_after_ordinal = :budgetAfterOrdinal,"
                        + " updated_at = clock_timestamp()"
                        + " where issue_id = :issueId and state = :state")
                .param("maxRuns", budget.getMaxRuns())
                .param("budgetAfterOrdinal", budget.getBudgetAfterOrdinal())
                .param("issueId", budget.getIssueId())
                .param("state", budget.getState())
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(budget.getIssueId());
    }
    return changed;
  }

  public boolean delete(UUID issueId, String state) {
    boolean changed =
        jdbc.sql(
                    "delete from project_issue_stage_budget"
                        + " where issue_id = :issueId and state = :state")
                .param("issueId", issueId)
                .param("state", state)
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

  public List<IssueStageBudgetRow> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_stage_budget"
                + " where issue_id = :issueId order by state asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }
}
