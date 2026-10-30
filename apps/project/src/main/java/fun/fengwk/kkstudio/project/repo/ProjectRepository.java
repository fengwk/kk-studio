package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.Project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code project} 聚合根持久化。
 *
 * <p>workflow 整体保存为严格 JSON 文本，通过行版本 CAS 更新；Issue 编号由 {@code next_issue_number} 单调分配。配置更新与归档都在
 * Project 行锁与版本检查下进行，影响执行的结构调整由服务要求项目无活动主 Run。
 */
@Repository
public class ProjectRepository {

  private static final String COLUMNS =
      "id, title, description, workflow, yolo_enabled, next_issue_number, version,"
          + " archived_at, created_at, updated_at";

  private static final RowMapper<Project> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        Long nextIssueNumber = ProjectRows.nullableLong(resultSet, "next_issue_number");
        Long version = ProjectRows.nullableLong(resultSet, "version");
        Boolean yoloEnabled = ProjectRows.nullableBoolean(resultSet, "yolo_enabled");
        return Project.builder()
            .id(ProjectRows.uuid(resultSet, "id"))
            .title(resultSet.getString("title"))
            .description(resultSet.getString("description"))
            .workflowJson(resultSet.getString("workflow"))
            .yoloEnabled(yoloEnabled != null && yoloEnabled)
            .nextIssueNumber(nextIssueNumber != null ? nextIssueNumber : 1L)
            .version(version != null ? version : 0L)
            .archivedAt(ProjectRows.nullableInstant(resultSet, "archived_at"))
            .createdAt(ProjectRows.nullableInstant(resultSet, "created_at"))
            .updatedAt(ProjectRows.nullableInstant(resultSet, "updated_at"))
            .build();
      };

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public ProjectRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  public boolean create(Project project) {
    boolean changed =
        jdbc.sql(
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
                .param("yoloEnabled", project.isYoloEnabled())
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(project.getId());
    }
    return changed;
  }

  public Project getById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public Project lockById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public Project lockForShare(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id for share")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public Project lockForKeyShare(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project where id = :id for key share")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /** 以版本 CAS 更新标题、描述、workflow JSON 与 YOLO 策略。 */
  public boolean updateConfiguration(Project project, long expectedVersion) {
    boolean changed =
        jdbc.sql(
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
                .param("yoloEnabled", project.isYoloEnabled())
                .param("id", project.getId())
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(project.getId());
    }
    return changed;
  }

  public boolean updateArchivedAt(UUID id, Instant archivedAt, long expectedVersion) {
    boolean changed =
        jdbc.sql(
                    "update project set archived_at = :archivedAt,"
                        + " updated_at = clock_timestamp(),"
                        + " version = version + 1"
                        + " where id = :id and version = :expectedVersion")
                .param("archivedAt", ProjectRows.timestamp(archivedAt))
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(id);
    }
    return changed;
  }

  /** 原子分配并返回下一个 Issue 编号（推进 {@code next_issue_number}）。 */
  public long allocateNextIssueNumber(UUID projectId) {
    Long allocated =
        jdbc.sql(
                "update project"
                    + " set next_issue_number = next_issue_number + 1,"
                    + " updated_at = clock_timestamp()"
                    + " where id = :projectId"
                    + " returning next_issue_number - 1")
            .param("projectId", projectId)
            .query((resultSet, rowNumber) -> resultSet.getLong(1))
            .optional()
            .orElse(null);
    if (allocated == null) {
      throw new IllegalStateException("project " + projectId + " disappeared during allocation");
    }
    return allocated;
  }

  public List<Project> listAll() {
    return jdbc.sql("select " + COLUMNS + " from project order by updated_at desc, id asc")
        .query(ROW_MAPPER)
        .list();
  }

  public List<Project> listByArchived(boolean archived) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project where (archived_at is not null) = :archived"
                + " order by updated_at desc, id asc")
        .param("archived", archived)
        .query(ROW_MAPPER)
        .list();
  }

  public boolean deleteById(UUID id, long expectedVersion) {
    boolean changed =
        jdbc.sql("delete from project where id = :id and version = :expectedVersion")
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(id);
    }
    return changed;
  }
}
