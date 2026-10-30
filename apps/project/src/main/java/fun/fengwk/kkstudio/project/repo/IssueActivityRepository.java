package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue_activity} 持久化：有序、幂等的 Issue 事实流。
 *
 * <p>序号由 Issue 行的分配游标决定；同一 Issue 的 {@code idempotencyKey} 唯一决定重放语义。写入与删除在同一事务内发送项目快照失效信号。
 */
@Repository
public class IssueActivityRepository {

  private static final String COLUMNS =
      "issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data,"
          + " idempotency_key, request_hash, created_at";

  private static final RowMapper<IssueActivity> ROW_MAPPER =
      (resultSet, rowNumber) ->
          IssueActivity.builder()
              .issueId(ProjectRows.uuid(resultSet, "issue_id"))
              .sequence(
                  resultSet.getObject("sequence", Long.class) != null
                      ? resultSet.getObject("sequence", Long.class)
                      : 0L)
              .kind(
                  resultSet.getString("kind") != null
                      ? IssueActivityKind.valueOf(resultSet.getString("kind"))
                      : null)
              .actorType(
                  resultSet.getString("actor_type") != null
                      ? IssueActivityActorType.valueOf(resultSet.getString("actor_type"))
                      : null)
              .actorAgentName(resultSet.getString("actor_agent_name"))
              .runId(ProjectRows.uuid(resultSet, "run_id"))
              .body(resultSet.getString("body"))
              .data(resultSet.getString("data"))
              .idempotencyKey(resultSet.getString("idempotency_key"))
              .requestHash(resultSet.getString("request_hash"))
              .createdAt(ProjectRows.nullableInstant(resultSet, "created_at"))
              .build();

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public IssueActivityRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  public boolean insert(IssueActivity activity) {
    boolean changed =
        jdbc.sql(
                    "insert into project_issue_activity ("
                        + "issue_id, sequence, kind, actor_type, actor_agent_name, run_id, body, data,"
                        + " idempotency_key, request_hash, created_at"
                        + ") values ("
                        + ":issueId, :sequence, :kind, :actorType, :actorAgentName, :runId, :body,"
                        + " :data::jsonb, :idempotencyKey, :requestHash, clock_timestamp())")
                .param("issueId", activity.getIssueId())
                .param("sequence", activity.getSequence())
                .param("kind", activity.getKind() != null ? activity.getKind().name() : null)
                .param(
                    "actorType",
                    activity.getActorType() != null ? activity.getActorType().name() : null)
                .param("actorAgentName", activity.getActorAgentName())
                .param("runId", activity.getRunId())
                .param("body", activity.getBody())
                .param("data", activity.getData())
                .param("idempotencyKey", activity.getIdempotencyKey())
                .param("requestHash", activity.getRequestHash())
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(activity.getIssueId());
    }
    return changed;
  }

  /**
   * 按同 Issue 的请求键读取既有活动；用于请求键去重（fail closed，不静默重放）。
   *
   * <p>锁前快速路径与 owner 锁后的 receipt 判定共用本读取：READ COMMITTED 下每次调用都是新的语句快照，因此锁后这次能看见锁等待期间提交的活动。
   */
  public IssueActivity findByIdempotencyKey(UUID issueId, String idempotencyKey) {
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

  public List<IssueActivity> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_activity where issue_id = :issueId"
                + " order by sequence asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  /** 按序号升序读取有界窗口 {@code (afterSequence, ...]}：投递游标只推进真正检视过的窗口。 */
  public List<IssueActivity> listPage(UUID issueId, long afterSequence, int limit) {
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
    int changed =
        jdbc.sql("delete from project_issue_activity where issue_id = :issueId")
            .param("issueId", issueId)
            .update();
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }
}
