package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.IssueDO;

import java.util.List;
import java.util.UUID;

/** {@code project_issue} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class IssueJdbcStore {

  private static final String COLUMNS =
      "id, project_id, number, title, description, state, blocked_from_state, block_reason,"
          + " pause_reason, pause_detail, next_run_ordinal, next_activity_sequence, version,"
          + " archived_at, created_at, updated_at";

  private static final RowMapper<IssueDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        IssueDO row = new IssueDO();
        row.setId(ProjectRows.uuid(resultSet, "id"));
        row.setProjectId(ProjectRows.uuid(resultSet, "project_id"));
        row.setNumber(ProjectRows.nullableLong(resultSet, "number"));
        row.setTitle(resultSet.getString("title"));
        row.setDescription(resultSet.getString("description"));
        row.setState(resultSet.getString("state"));
        row.setBlockedFromState(resultSet.getString("blocked_from_state"));
        row.setBlockReason(resultSet.getString("block_reason"));
        row.setPauseReason(resultSet.getString("pause_reason"));
        row.setPauseDetail(resultSet.getString("pause_detail"));
        row.setNextRunOrdinal(ProjectRows.nullableLong(resultSet, "next_run_ordinal"));
        row.setNextActivitySequence(ProjectRows.nullableLong(resultSet, "next_activity_sequence"));
        row.setVersion(ProjectRows.nullableLong(resultSet, "version"));
        row.setArchivedAt(ProjectRows.nullableInstant(resultSet, "archived_at"));
        row.setCreatedAt(ProjectRows.nullableInstant(resultSet, "created_at"));
        row.setUpdatedAt(ProjectRows.nullableInstant(resultSet, "updated_at"));
        return row;
      };

  private final JdbcClient jdbc;

  public IssueJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(IssueDO issue) {
    return jdbc.sql(
            "insert into project_issue ("
                + "id, project_id, number, title, description, state, blocked_from_state,"
                + " block_reason, pause_reason, pause_detail, next_run_ordinal,"
                + " next_activity_sequence, version, archived_at, created_at, updated_at"
                + ") values ("
                + ":id, :projectId, :number, :title, :description, :state, null,"
                + " null, null, null, 1, 1, 0, null, clock_timestamp(), clock_timestamp())")
        .param("id", issue.getId())
        .param("projectId", issue.getProjectId())
        .param("number", issue.getNumber())
        .param("title", issue.getTitle())
        .param("description", issue.getDescription())
        .param("state", issue.getState())
        .update();
  }

  /** 读取当前已提交的 Issue 行：每次语句都取得新的 READ COMMITTED 快照，不会被早先查询遮蔽。 */
  public IssueDO getById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueDO lockById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public IssueDO getByProjectAndNumber(UUID projectId, long number) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue where project_id = :projectId and number = :number")
        .param("projectId", projectId)
        .param("number", number)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public List<IssueDO> listByProjectId(UUID projectId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue where project_id = :projectId order by number asc")
        .param("projectId", projectId)
        .query(ROW_MAPPER)
        .list();
  }

  public List<IssueDO> listByProjectIdAndArchived(UUID projectId, boolean archived) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue where project_id = :projectId"
                + " and (archived_at is not null) = :archived order by number asc")
        .param("projectId", projectId)
        .param("archived", archived)
        .query(ROW_MAPPER)
        .list();
  }

  public int updateById(IssueDO issue, long expectedVersion) {
    return jdbc.sql(
            "update project_issue"
                + " set title = :title,"
                + " description = :description,"
                + " state = :state,"
                + " blocked_from_state = :blockedFromState,"
                + " block_reason = :blockReason,"
                + " pause_reason = :pauseReason,"
                + " pause_detail = :pauseDetail,"
                + " next_run_ordinal = :nextRunOrdinal,"
                + " next_activity_sequence = :nextActivitySequence,"
                + " archived_at = :archivedAt,"
                + " updated_at = clock_timestamp(),"
                + " version = version + 1"
                + " where id = :id and version = :expectedVersion")
        .param("title", issue.getTitle())
        .param("description", issue.getDescription())
        .param("state", issue.getState())
        .param("blockedFromState", issue.getBlockedFromState())
        .param("blockReason", issue.getBlockReason())
        .param("pauseReason", issue.getPauseReason())
        .param("pauseDetail", issue.getPauseDetail())
        .param("nextRunOrdinal", issue.getNextRunOrdinal())
        .param("nextActivitySequence", issue.getNextActivitySequence())
        .param("archivedAt", ProjectRows.timestamp(issue.getArchivedAt()))
        .param("id", issue.getId())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int deleteById(UUID id, long expectedVersion) {
    return jdbc.sql("delete from project_issue where id = :id and version = :expectedVersion")
        .param("id", id)
        .param("expectedVersion", expectedVersion)
        .update();
  }
}
