package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.domain.IssueRunStatus;
import fun.fengwk.kkstudio.project.model.IssueRun;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue_run} 持久化。
 *
 * <p>同一 Issue 至多一个 RUNNING/WAITING 主 Run（部分唯一索引），终态收尾以 {@code version} CAS 推进。
 */
@Repository
public class IssueRunRepository {

  private static final String COLUMNS =
      "id, issue_id, ordinal, state, session_id, thread_id, status, start_entry_id, end_entry_id,"
          + " final_answer_entry_id, next_state, observed_activity_sequence,"
          + " remaining_execution_ms, active_since, error, version, started_at, ended_at";

  private static final RowMapper<IssueRun> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        Long ordinal = ProjectRows.nullableLong(resultSet, "ordinal");
        Long observedActivitySequence =
            ProjectRows.nullableLong(resultSet, "observed_activity_sequence");
        Long remainingExecutionMs = ProjectRows.nullableLong(resultSet, "remaining_execution_ms");
        Long version = ProjectRows.nullableLong(resultSet, "version");
        String status = resultSet.getString("status");
        return IssueRun.builder()
            .id(ProjectRows.uuid(resultSet, "id"))
            .issueId(ProjectRows.uuid(resultSet, "issue_id"))
            .ordinal(ordinal != null ? ordinal : 0L)
            .state(resultSet.getString("state"))
            .sessionId(ProjectRows.uuid(resultSet, "session_id"))
            .threadId(ProjectRows.uuid(resultSet, "thread_id"))
            .status(status != null ? IssueRunStatus.valueOf(status) : null)
            .startEntryId(ProjectRows.uuid(resultSet, "start_entry_id"))
            .endEntryId(ProjectRows.uuid(resultSet, "end_entry_id"))
            .finalAnswerEntryId(ProjectRows.uuid(resultSet, "final_answer_entry_id"))
            .nextState(resultSet.getString("next_state"))
            .observedActivitySequence(
                observedActivitySequence != null ? observedActivitySequence : 0L)
            .remainingExecutionMs(remainingExecutionMs != null ? remainingExecutionMs : 0L)
            .activeSince(ProjectRows.nullableInstant(resultSet, "active_since"))
            .error(resultSet.getString("error"))
            .version(version != null ? version : 0L)
            .startedAt(ProjectRows.nullableInstant(resultSet, "started_at"))
            .endedAt(ProjectRows.nullableInstant(resultSet, "ended_at"))
            .build();
      };

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public IssueRunRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  public boolean insert(IssueRun run) {
    boolean changed =
        jdbc.sql(
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
                .param("status", run.getStatus() != null ? run.getStatus().name() : null)
                .param("startEntryId", run.getStartEntryId())
                .param("endEntryId", run.getEndEntryId())
                .param("finalAnswerEntryId", run.getFinalAnswerEntryId())
                .param("nextState", run.getNextState())
                .param("observedActivitySequence", run.getObservedActivitySequence())
                .param("remainingExecutionMs", run.getRemainingExecutionMs())
                .param("activeSince", ProjectRows.timestamp(run.getActiveSince()))
                .param("error", run.getError())
                .param("endedAt", ProjectRows.timestamp(run.getEndedAt()))
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(run.getIssueId());
    }
    return changed;
  }

  /** 按 id 读取 Run；未提交的并发收尾不可见，每次调用都是新的语句快照。 */
  public IssueRun getById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue_run where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueRun lockById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue_run where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueRun getActiveByIssueId(UUID issueId) {
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

  public IssueRun lockActiveByIssueId(UUID issueId) {
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

  public IssueRun getLatestByIssueId(UUID issueId) {
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

  public List<IssueRun> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_run where issue_id = :issueId order by ordinal asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  /** 本 Issue 指定阶段中序号大于高水位的 Run 数（不论 Agent 与状态），用于阶段额度计算。 */
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

  public boolean updateById(IssueRun run, long expectedVersion) {
    boolean changed =
        jdbc.sql(
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
                .param("status", run.getStatus() != null ? run.getStatus().name() : null)
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
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(run.getIssueId());
    }
    return changed;
  }

  public boolean deleteById(UUID id, long expectedVersion) {
    IssueRun run = getById(id);
    boolean changed =
        jdbc.sql("delete from project_issue_run where id = :id and version = :expectedVersion")
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(run.getIssueId());
    }
    return changed;
  }

  public int deleteByIssueId(UUID issueId) {
    int changed =
        jdbc.sql("delete from project_issue_run where issue_id = :issueId")
            .param("issueId", issueId)
            .update();
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }
}
