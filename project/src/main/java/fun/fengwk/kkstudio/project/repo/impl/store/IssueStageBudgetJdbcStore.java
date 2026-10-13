package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueStageBudgetDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_stage_budget} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class IssueStageBudgetJdbcStore {

  private static final String COLUMNS = "issue_id, state, max_runs, budget_after_ordinal";

  private static final RowMapper<IssueStageBudgetDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueStageBudgetDO row = new IssueStageBudgetDO();
        row.setIssueId(ProjectRows.uuid(resultSet, "issue_id"));
        row.setState(resultSet.getString("state"));
        row.setMaxRuns(ProjectRows.nullableInteger(resultSet, "max_runs"));
        row.setBudgetAfterOrdinal(ProjectRows.nullableLong(resultSet, "budget_after_ordinal"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueStageBudgetJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public IssueStageBudgetDO get(UUID issueId, String state) {
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

  public List<IssueStageBudgetDO> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_stage_budget"
                + " where issue_id = :issueId order by state asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  public int insert(IssueStageBudgetDO budget) {
    return jdbc.sql(
            "insert into project_issue_stage_budget ("
                + "issue_id, state, max_runs, budget_after_ordinal, created_at, updated_at"
                + ") values ("
                + ":issueId, :state, :maxRuns, :budgetAfterOrdinal,"
                + " clock_timestamp(), clock_timestamp())")
        .param("issueId", budget.getIssueId())
        .param("state", budget.getState())
        .param("maxRuns", budget.getMaxRuns())
        .param("budgetAfterOrdinal", budget.getBudgetAfterOrdinal())
        .update();
  }

  public int update(IssueStageBudgetDO budget) {
    return jdbc.sql(
            "update project_issue_stage_budget"
                + " set max_runs = :maxRuns,"
                + " budget_after_ordinal = :budgetAfterOrdinal,"
                + " updated_at = clock_timestamp()"
                + " where issue_id = :issueId and state = :state")
        .param("maxRuns", budget.getMaxRuns())
        .param("budgetAfterOrdinal", budget.getBudgetAfterOrdinal())
        .param("issueId", budget.getIssueId())
        .param("state", budget.getState())
        .update();
  }

  public int delete(UUID issueId, String state) {
    return jdbc.sql(
            "delete from project_issue_stage_budget"
                + " where issue_id = :issueId and state = :state")
        .param("issueId", issueId)
        .param("state", state)
        .update();
  }

  public int deleteByIssueId(UUID issueId) {
    return jdbc.sql("delete from project_issue_stage_budget where issue_id = :issueId")
        .param("issueId", issueId)
        .update();
  }
}
