package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueEvidence;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_evidence} 持久化：Issue 显式公开的 Blob 证据引用。 */
@Repository
public class IssueEvidenceRepository {

  private static final String COLUMNS =
      "issue_id, blob_id, actor_agent_name, run_id, name, created_at";

  private static final RowMapper<IssueEvidence> ROW_MAPPER =
      (resultSet, rowNumber) ->
          IssueEvidence.builder()
              .issueId(ProjectRows.uuid(resultSet, "issue_id"))
              .blobId(ProjectRows.uuid(resultSet, "blob_id"))
              .actorAgentName(resultSet.getString("actor_agent_name"))
              .runId(ProjectRows.uuid(resultSet, "run_id"))
              .name(resultSet.getString("name"))
              .createdAt(ProjectRows.nullableInstant(resultSet, "created_at"))
              .build();

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public IssueEvidenceRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  /**
   * 插入证据行（仅首次发布生效）。
   *
   * @param evidence 证据实体
   * @return true 表示成功插入新行，false 表示 (issue_id, blob_id) 已存在
   */
  public boolean insert(IssueEvidence evidence) {
    boolean changed =
        jdbc.sql(
                    "insert into project_issue_evidence ("
                        + "issue_id, blob_id, actor_agent_name, run_id, name, created_at"
                        + ") values ("
                        + ":issueId, :blobId, :actorAgentName, :runId, :name, clock_timestamp()"
                        + ") on conflict (issue_id, blob_id) do nothing")
                .param("issueId", evidence.getIssueId())
                .param("blobId", evidence.getBlobId())
                .param("actorAgentName", evidence.getActorAgentName())
                .param("runId", evidence.getRunId())
                .param("name", evidence.getName())
                .update()
            == 1;
    if (changed) {
      notifier.issueChanged(evidence.getIssueId());
    }
    return changed;
  }

  /** 读取指定 Issue 与 Blob 的证据记录。 */
  public IssueEvidence get(UUID issueId, UUID blobId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_evidence where issue_id = :issueId and blob_id = :blobId")
        .param("issueId", issueId)
        .param("blobId", blobId)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /** 列举指定 Issue 的全部证据（按发布时间倒序）。 */
  public List<IssueEvidence> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_evidence where issue_id = :issueId"
                + " order by created_at desc, blob_id asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  /** 删除指定 Issue 的全部证据行，返回删除条数。 */
  public int deleteByIssueId(UUID issueId) {
    int changed =
        jdbc.sql("delete from project_issue_evidence where issue_id = :issueId")
            .param("issueId", issueId)
            .update();
    if (changed > 0) {
      notifier.issueChanged(issueId);
    }
    return changed;
  }
}
