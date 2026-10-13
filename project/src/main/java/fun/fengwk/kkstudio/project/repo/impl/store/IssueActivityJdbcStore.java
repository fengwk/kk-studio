package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueActivityDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_activity} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class IssueActivityJdbcStore {

  private static final String COLUMNS =
      "issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data,"
          + " idempotency_key, request_hash, created_at";

  private static final RowMapper<IssueActivityDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueActivityDO row = new IssueActivityDO();
        row.setIssueId(ProjectRows.uuid(resultSet, "issue_id"));
        row.setSequence(ProjectRows.nullableLong(resultSet, "sequence"));
        row.setKind(resultSet.getString("kind"));
        row.setActorType(resultSet.getString("actor_type"));
        row.setActorAgentName(resultSet.getString("actor_agent_name"));
        row.setRunId(ProjectRows.uuid(resultSet, "run_id"));
        row.setBody(resultSet.getString("body"));
        row.setData(resultSet.getString("data"));
        row.setIdempotencyKey(resultSet.getString("idempotency_key"));
        row.setRequestHash(resultSet.getString("request_hash"));
        row.setCreatedAt(ProjectRows.nullableInstant(resultSet, "created_at"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueActivityJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(IssueActivityDO activity) {
    return jdbc.sql(
            "insert into project_issue_activity ("
                + "issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data,"
                + " idempotency_key, request_hash, created_at"
                + ") values ("
                + ":issueId, :sequence, :kind, :actorType, :actorAgentName, :runId, :body,"
                + " :data::jsonb, :idempotencyKey, :requestHash, clock_timestamp())")
        .param("issueId", activity.getIssueId())
        .param("sequence", activity.getSequence())
        .param("kind", activity.getKind())
        .param("actorType", activity.getActorType())
        .param("actorAgentName", activity.getActorAgentName())
        .param("runId", activity.getRunId())
        .param("body", activity.getBody())
        .param("data", activity.getData())
        .param("idempotencyKey", activity.getIdempotencyKey())
        .param("requestHash", activity.getRequestHash())
        .update();
  }

  /** 按同 Issue 请求键读取活动；每次语句都取得新的 READ COMMITTED 快照，锁后重读可见等待期间提交的行。 */
  public IssueActivityDO findByIdempotencyKey(UUID issueId, String idempotencyKey) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_activity where issue_id = :issueId"
                + " and idempotency_key = :idempotencyKey")
        .param("issueId", issueId)
        .param("idempotencyKey", idempotencyKey)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public List<IssueActivityDO> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_activity where issue_id = :issueId"
                + " order by sequence asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  public List<IssueActivityDO> listPage(UUID issueId, long afterSequence, int limit) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_activity where issue_id = :issueId"
                + " and sequence > :afterSequence order by sequence asc limit :limit")
        .param("issueId", issueId)
        .param("afterSequence", afterSequence)
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  public long countByIssueId(UUID issueId) {
    return jdbc.sql("select count(*) from project_issue_activity where issue_id = :issueId")
        .param("issueId", issueId)
        .query((resultSet, rowNumber) -> resultSet.getLong(1))
        .single();
  }

  public int deleteByIssueId(UUID issueId) {
    return jdbc.sql("delete from project_issue_activity where issue_id = :issueId")
        .param("issueId", issueId)
        .update();
  }
}
