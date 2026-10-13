package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueRunDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_run} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class IssueRunJdbcStore {

  private static final String COLUMNS =
      "id, issue_id, ordinal, state, session_id, thread_id, status, start_entry_id, end_entry_id,"
          + " final_answer_entry_id, next_state, observed_activity_sequence,"
          + " remaining_execution_ms, active_since, error, version, started_at, ended_at";

  private static final RowMapper<IssueRunDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueRunDO row = new IssueRunDO();
        row.setId(ProjectRows.uuid(resultSet, "id"));
        row.setIssueId(ProjectRows.uuid(resultSet, "issue_id"));
        row.setOrdinal(ProjectRows.nullableLong(resultSet, "ordinal"));
        row.setState(resultSet.getString("state"));
        row.setSessionId(ProjectRows.uuid(resultSet, "session_id"));
        row.setThreadId(ProjectRows.uuid(resultSet, "thread_id"));
        row.setStatus(resultSet.getString("status"));
        row.setStartEntryId(ProjectRows.uuid(resultSet, "start_entry_id"));
        row.setEndEntryId(ProjectRows.uuid(resultSet, "end_entry_id"));
        row.setFinalAnswerEntryId(ProjectRows.uuid(resultSet, "final_answer_entry_id"));
        row.setNextState(resultSet.getString("next_state"));
        row.setObservedActivitySequence(
            ProjectRows.nullableLong(resultSet, "observed_activity_sequence"));
        row.setRemainingExecutionMs(ProjectRows.nullableLong(resultSet, "remaining_execution_ms"));
        row.setActiveSince(ProjectRows.nullableInstant(resultSet, "active_since"));
        row.setError(resultSet.getString("error"));
        row.setVersion(ProjectRows.nullableLong(resultSet, "version"));
        row.setStartedAt(ProjectRows.nullableInstant(resultSet, "started_at"));
        row.setEndedAt(ProjectRows.nullableInstant(resultSet, "ended_at"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueRunJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(IssueRunDO run) {
    return jdbc.sql(
            "insert into project_issue_run ("
                + "id, issue_id, ordinal, state, session_id, thread_id, status, start_entry_id,"
                + " end_entry_id, final_answer_entry_id, next_state, observed_activity_sequence,"
                + " remaining_execution_ms, active_since, error, version, started_at, ended_at"
                + ") values ("
                + ":id, :issueId, :ordinal, :state, :sessionId, :threadId, :status,"
                + " :startEntryId, :endEntryId, :finalAnswerEntryId, :nextState,"
                + " :observedActivitySequence, :remainingExecutionMs, :activeSince, :error,"
                + " 0, clock_timestamp(), :endedAt)")
        .param("id", run.getId())
        .param("issueId", run.getIssueId())
        .param("ordinal", run.getOrdinal())
        .param("state", run.getState())
        .param("sessionId", run.getSessionId())
        .param("threadId", run.getThreadId())
        .param("status", run.getStatus())
        .param("startEntryId", run.getStartEntryId())
        .param("endEntryId", run.getEndEntryId())
        .param("finalAnswerEntryId", run.getFinalAnswerEntryId())
        .param("nextState", run.getNextState())
        .param("observedActivitySequence", run.getObservedActivitySequence())
        .param("remainingExecutionMs", run.getRemainingExecutionMs())
        .param("activeSince", ProjectRows.timestamp(run.getActiveSince()))
        .param("error", run.getError())
        .param("endedAt", ProjectRows.timestamp(run.getEndedAt()))
        .update();
  }

  /** 读取当前已提交的 Run 行：每次语句都取得新的 READ COMMITTED 快照，不会被早先查询遮蔽。 */
  public IssueRunDO getById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue_run where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueRunDO lockById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue_run where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueRunDO getActiveByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_run where issue_id = :issueId"
                + " and status in ('RUNNING', 'WAITING')")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueRunDO lockActiveByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_run where issue_id = :issueId"
                + " and status in ('RUNNING', 'WAITING') for update")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueRunDO getLatestByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_run where issue_id = :issueId"
                + " order by ordinal desc limit 1")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public List<IssueRunDO> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_run where issue_id = :issueId order by ordinal asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  public long countByIssueIdAndStateAfterOrdinal(UUID issueId, String state, long afterOrdinal) {
    return jdbc.sql(
            "select count(*) from project_issue_run where issue_id = :issueId"
                + " and state = :state and ordinal > :afterOrdinal")
        .param("issueId", issueId)
        .param("state", state)
        .param("afterOrdinal", afterOrdinal)
        .query((resultSet, rowNumber) -> resultSet.getLong(1))
        .single();
  }

  public int updateById(IssueRunDO run, long expectedVersion) {
    return jdbc.sql(
            "update project_issue_run"
                + " set status = :status,"
                + " end_entry_id = :endEntryId,"
                + " final_answer_entry_id = :finalAnswerEntryId,"
                + " next_state = :nextState,"
                + " observed_activity_sequence = :observedActivitySequence,"
                + " remaining_execution_ms = :remainingExecutionMs,"
                + " active_since = :activeSince,"
                + " error = :error,"
                + " ended_at = :endedAt,"
                + " version = version + 1"
                + " where id = :id and version = :expectedVersion")
        .param("status", run.getStatus())
        .param("endEntryId", run.getEndEntryId())
        .param("finalAnswerEntryId", run.getFinalAnswerEntryId())
        .param("nextState", run.getNextState())
        .param("observedActivitySequence", run.getObservedActivitySequence())
        .param("remainingExecutionMs", run.getRemainingExecutionMs())
        .param("activeSince", ProjectRows.timestamp(run.getActiveSince()))
        .param("error", run.getError())
        .param("endedAt", ProjectRows.timestamp(run.getEndedAt()))
        .param("id", run.getId())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteById(UUID id, long expectedVersion) {
    return jdbc.sql("delete from project_issue_run where id = :id and version = :expectedVersion")
        .param("id", id)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteByIssueId(UUID issueId) {
    return jdbc.sql("delete from project_issue_run where issue_id = :issueId")
        .param("issueId", issueId)
        .update();
  }
}
