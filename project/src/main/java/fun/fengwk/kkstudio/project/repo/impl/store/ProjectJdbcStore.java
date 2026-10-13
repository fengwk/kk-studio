package fun.fengwk.kkstudio.project.repo.impl.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.project.repo.impl.model.ProjectDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code project} 表的 Spring JDBC 存取：显式 SQL 与 {@link RowMapper}。 */
@Component
public class ProjectJdbcStore {

  private static final String COLUMNS =
      "id, title, description, workflow, yolo_enabled, next_issue_number, version,"
          + " archived_at, created_at, updated_at";

  private static final RowMapper<ProjectDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        ProjectDO row = new ProjectDO();
        row.setId(ProjectRows.uuid(resultSet, "id"));
        row.setTitle(resultSet.getString("title"));
        row.setDescription(resultSet.getString("description"));
        row.setWorkflowJson(resultSet.getString("workflow"));
        row.setYoloEnabled(ProjectRows.nullableBoolean(resultSet, "yolo_enabled"));
        row.setNextIssueNumber(ProjectRows.nullableLong(resultSet, "next_issue_number"));
        row.setVersion(ProjectRows.nullableLong(resultSet, "version"));
        row.setArchivedAt(ProjectRows.nullableInstant(resultSet, "archived_at"));
        row.setCreatedAt(ProjectRows.nullableInstant(resultSet, "created_at"));
        row.setUpdatedAt(ProjectRows.nullableInstant(resultSet, "updated_at"));
        return row;
      };

  private final JdbcClient jdbc;

  public ProjectJdbcStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public int insert(ProjectDO project) {
    return jdbc.sql(
            "insert into project ("
                + "id, title, description, workflow, yolo_enabled, next_issue_number, version,"
                + " archived_at, created_at, updated_at"
                + ") values ("
                + ":id, :title, :description, :workflowJson::jsonb, :yoloEnabled, 1, 0,"
                + " null, clock_timestamp(), clock_timestamp())")
        .param("id", project.getId())
        .param("title", project.getTitle())
        .param("description", project.getDescription())
        .param("workflowJson", project.getWorkflowJson())
        .param("yoloEnabled", project.getYoloEnabled())
        .update();
  }

  public ProjectDO getById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public ProjectDO lockById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public ProjectDO lockForShare(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id for share")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public ProjectDO lockForKeyShare(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id for key share")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public int updateConfiguration(ProjectDO project, long expectedVersion) {
    return jdbc.sql(
            "update project set title = :title,"
                + " description = :description,"
                + " workflow = :workflowJson::jsonb,"
                + " yolo_enabled = :yoloEnabled,"
                + " updated_at = clock_timestamp(),"
                + " version = version + 1"
                + " where id = :id and version = :expectedVersion")
        .param("title", project.getTitle())
        .param("description", project.getDescription())
        .param("workflowJson", project.getWorkflowJson())
        .param("yoloEnabled", project.getYoloEnabled())
        .param("id", project.getId())
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public int updateArchivedAt(UUID id, Instant archivedAt, long expectedVersion) {
    return jdbc.sql(
            "update project set archived_at = :archivedAt,"
                + " updated_at = clock_timestamp(),"
                + " version = version + 1"
                + " where id = :id and version = :expectedVersion")
        .param("archivedAt", ProjectRows.timestamp(archivedAt))
        .param("id", id)
        .param("expectedVersion", expectedVersion)
        .update();
  }

  public Long allocateNextIssueNumber(UUID projectId) {
    return jdbc.sql(
            "update project"
                + " set next_issue_number = next_issue_number + 1, updated_at = clock_timestamp()"
                + " where id = :projectId"
                + " returning next_issue_number - 1")
        .param("projectId", projectId)
        .query((resultSet, rowNumber) -> resultSet.getLong(1))
        .optional()
        .orElse(null);
  }

  public List<ProjectDO> listAll() {
    return jdbc.sql("select " + COLUMNS + " from project order by updated_at desc, id asc")
        .query(ROW_MAPPER)
        .list();
  }

  public List<ProjectDO> listByArchived(boolean archived) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project where (archived_at is not null) = :archived"
                + " order by updated_at desc, id asc")
        .param("archived", archived)
        .query(ROW_MAPPER)
        .list();
  }

  public int deleteById(UUID id, long expectedVersion) {
    return jdbc.sql("delete from project where id = :id and version = :expectedVersion")
        .param("id", id)
        .param("expectedVersion", expectedVersion)
        .update();
  }
}
