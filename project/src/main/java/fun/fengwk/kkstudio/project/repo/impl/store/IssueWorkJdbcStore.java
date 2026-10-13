package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueWorkDO;

import java.time.Duration;
import java.util.UUID;

/**
 * {@code project_issue_work} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。
 *
 * <p>调度与租约统一使用 {@code statement_timestamp()} 并截断到毫秒，避免 {@code timestamp(3)} 舍入把立即到期写到未来。动态部分只是由
 * Java 预计算的毫秒数命名参数，不拼接调用方输入。
 */
@Component
public class IssueWorkJdbcStore {

  private static final String COLUMNS =
      "issue_id, wake_version, due_at, lease_token, lease_until, created_at, updated_at";

  private static final RowMapper<IssueWorkDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueWorkDO row = new IssueWorkDO();
        row.setIssueId(ProjectRows.uuid(resultSet, "issue_id"));
        row.setWakeVersion(ProjectRows.nullableLong(resultSet, "wake_version"));
        row.setDueAt(ProjectRows.nullableInstant(resultSet, "due_at"));
        row.setLeaseToken(resultSet.getString("lease_token"));
        row.setLeaseUntil(ProjectRows.nullableInstant(resultSet, "lease_until"));
        row.setCreatedAt(ProjectRows.nullableInstant(resultSet, "created_at"));
        row.setUpdatedAt(ProjectRows.nullableInstant(resultSet, "updated_at"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueWorkJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public IssueWorkDO getById(UUID issueId) {
    return jdbc.sql("select " + COLUMNS + " from project_issue_work where issue_id = :issueId")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueWorkDO lockById(UUID issueId) {
    return jdbc.sql(
            "select " + COLUMNS + " from project_issue_work where issue_id = :issueId for update")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueWorkDO upsertRequest(UUID issueId, Duration delay) {
    return jdbc.sql(
            "insert into project_issue_work ("
                + "issue_id, wake_version, due_at, lease_token, lease_until, created_at, updated_at"
                + ") values ("
                + ":issueId, 1,"
                + " date_trunc('milliseconds', statement_timestamp())"
                + " + :delayMillis * interval '1 millisecond', null, null,"
                + " date_trunc('milliseconds', statement_timestamp()),"
                + " date_trunc('milliseconds', statement_timestamp())"
                + ") on conflict (issue_id) do update"
                + " set due_at = least(project_issue_work.due_at, excluded.due_at),"
                + " wake_version = project_issue_work.wake_version + 1,"
                + " updated_at = date_trunc('milliseconds', statement_timestamp())"
                + " returning "
                + COLUMNS)
        .param("issueId", issueId)
        .param("delayMillis", delay.toMillis())
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueWorkDO claimNext(String leaseToken, Duration leaseDuration) {
    return jdbc.sql(
            "with candidate as ("
                + " select issue_id"
                + " from project_issue_work"
                + " where due_at <= date_trunc('milliseconds', statement_timestamp())"
                + " and (lease_until is null"
                + " or lease_until <= date_trunc('milliseconds', statement_timestamp()))"
                + " order by due_at asc, issue_id asc"
                + " for update skip locked"
                + " limit 1"
                + ")"
                + " update project_issue_work work"
                + " set lease_token = :leaseToken,"
                + " lease_until = date_trunc('milliseconds', statement_timestamp())"
                + " + :leaseMillis * interval '1 millisecond',"
                + " updated_at = date_trunc('milliseconds', statement_timestamp())"
                + " from candidate"
                + " where work.issue_id = candidate.issue_id"
                + " returning work.issue_id, work.wake_version, work.due_at, work.lease_token,"
                + " work.lease_until, work.created_at, work.updated_at")
        .param("leaseToken", leaseToken)
        .param("leaseMillis", leaseDuration.toMillis())
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int renewLease(UUID issueId, String leaseToken, Duration leaseDuration) {
    return jdbc.sql(
            "update project_issue_work"
                + " set lease_until = greatest(lease_until,"
                + " date_trunc('milliseconds', statement_timestamp())"
                + " + :leaseMillis * interval '1 millisecond'),"
                + " updated_at = date_trunc('milliseconds', statement_timestamp())"
                + " where issue_id = :issueId"
                + " and lease_token = :leaseToken"
                + " and lease_until > date_trunc('milliseconds', statement_timestamp())")
        .param("leaseMillis", leaseDuration.toMillis())
        .param("issueId", issueId)
        .param("leaseToken", leaseToken)
        .update();
  }

  /**
   * 单条 CTE 内完成或释放：版本与租约匹配则删除，版本被新 wake 推进则释放租约并把 due 提前到当前时刻，围栏不匹配则不写。返回实际发生的写入结果，使调用方能在同一事务内区分
   * 「released 成功」与「完全未写」。
   */
  public IssueWorkCompletion completeWork(
      UUID issueId, String leaseToken, long claimedWakeVersion) {
    String outcome =
        jdbc.sql(
                "with deleted as ("
                    + " delete from project_issue_work"
                    + " where issue_id = :issueId"
                    + " and lease_token = :leaseToken"
                    + " and lease_until > date_trunc('milliseconds', statement_timestamp())"
                    + " and wake_version = :claimedWakeVersion"
                    + " returning issue_id"
                    + "), released as ("
                    + " update project_issue_work"
                    + " set lease_token = null, lease_until = null,"
                    + " due_at = least(due_at, date_trunc('milliseconds', statement_timestamp())),"
                    + " updated_at = date_trunc('milliseconds', statement_timestamp())"
                    + " where issue_id = :issueId"
                    + " and lease_token = :leaseToken"
                    + " and lease_until > date_trunc('milliseconds', statement_timestamp())"
                    + " and wake_version != :claimedWakeVersion"
                    + " returning issue_id"
                    + ")"
                    + " select case"
                    + " when exists(select 1 from deleted) then 'DELETED'"
                    + " when exists(select 1 from released) then 'RELEASED'"
                    + " else 'NONE'"
                    + " end")
            .param("issueId", issueId)
            .param("leaseToken", leaseToken)
            .param("claimedWakeVersion", claimedWakeVersion)
            .query((resultSet, rowNumber) -> resultSet.getString(1))
            .single();
    return IssueWorkCompletion.valueOf(outcome);
  }

  public int rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Duration delay) {
    return jdbc.sql(
            "update project_issue_work"
                + " set lease_token = null,"
                + " lease_until = null,"
                + " due_at = case when wake_version = :claimedWakeVersion"
                + " then date_trunc('milliseconds', statement_timestamp())"
                + " + :delayMillis * interval '1 millisecond'"
                + " else least(due_at, date_trunc('milliseconds', statement_timestamp())"
                + " + :delayMillis * interval '1 millisecond') end,"
                + " updated_at = date_trunc('milliseconds', statement_timestamp())"
                + " where issue_id = :issueId"
                + " and lease_token = :leaseToken"
                + " and lease_until > date_trunc('milliseconds', statement_timestamp())")
        .param("claimedWakeVersion", claimedWakeVersion)
        .param("delayMillis", delay.toMillis())
        .param("issueId", issueId)
        .param("leaseToken", leaseToken)
        .update();
  }

  public int deleteByIssueId(UUID issueId) {
    return jdbc.sql("delete from project_issue_work where issue_id = :issueId")
        .param("issueId", issueId)
        .update();
  }
}
