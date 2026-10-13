package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueEvidenceDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue_evidence} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class IssueEvidenceJdbcStore {

  private static final String COLUMNS =
      "issue_id, blob_id, actor_agent_name, run_id, name, created_at";

  private static final RowMapper<IssueEvidenceDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueEvidenceDO row = new IssueEvidenceDO();
        row.setIssueId(ProjectRows.uuid(resultSet, "issue_id"));
        row.setBlobId(ProjectRows.uuid(resultSet, "blob_id"));
        row.setActorAgentName(resultSet.getString("actor_agent_name"));
        row.setRunId(ProjectRows.uuid(resultSet, "run_id"));
        row.setName(resultSet.getString("name"));
        row.setCreatedAt(ProjectRows.nullableInstant(resultSet, "created_at"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueEvidenceJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(IssueEvidenceDO evidence) {
    return jdbc.sql(
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
        .update();
  }

  public IssueEvidenceDO get(UUID issueId, UUID blobId) {
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

  public List<IssueEvidenceDO> listByIssueId(UUID issueId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue_evidence where issue_id = :issueId"
                + " order by created_at desc, blob_id asc")
        .param("issueId", issueId)
        .query(ROW_MAPPER)
        .list();
  }

  public int deleteByIssueId(UUID issueId) {
    return jdbc.sql("delete from project_issue_evidence where issue_id = :issueId")
        .param("issueId", issueId)
        .update();
  }
}
