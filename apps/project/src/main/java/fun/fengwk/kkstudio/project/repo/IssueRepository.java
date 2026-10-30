package fun.fengwk.kkstudio.project.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.Issue;

import java.util.List;
import java.util.UUID;

/**
 * {@code project_issue} 聚合根持久化。
 *
 * <p>只有在事务内先以 {@link #lockById(UUID)} 取得行级 UPDATE 锁，才能以版本 CAS 写回状态、门禁与分配游标。
 */
@Repository
public class IssueRepository {

  private static final String COLUMNS =
      "id, project_id, number, title, description, state, blocked_from_state, block_reason,"
          + " pause_reason, pause_detail, next_run_ordinal, next_activity_sequence, version,"
          + " archived_at, created_at, updated_at";

  private static final RowMapper<Issue> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        Long number = ProjectRows.nullableLong(resultSet, "number");
        Long nextRunOrdinal = ProjectRows.nullableLong(resultSet, "next_run_ordinal");
        Long nextActivitySequence = ProjectRows.nullableLong(resultSet, "next_activity_sequence");
        Long version = ProjectRows.nullableLong(resultSet, "version");
        return Issue.builder()
            .id(ProjectRows.uuid(resultSet, "id"))
            .projectId(ProjectRows.uuid(resultSet, "project_id"))
            .number(number != null ? number : 0L)
            .title(resultSet.getString("title"))
            .description(resultSet.getString("description"))
            .state(resultSet.getString("state"))
            .blockedFromState(resultSet.getString("blocked_from_state"))
            .blockReason(resultSet.getString("block_reason"))
            .pauseReason(resultSet.getString("pause_reason"))
            .pauseDetail(resultSet.getString("pause_detail"))
            .nextRunOrdinal(nextRunOrdinal != null ? nextRunOrdinal : 1L)
            .nextActivitySequence(nextActivitySequence != null ? nextActivitySequence : 1L)
            .version(version != null ? version : 0L)
            .archivedAt(ProjectRows.nullableInstant(resultSet, "archived_at"))
            .createdAt(ProjectRows.nullableInstant(resultSet, "created_at"))
            .updatedAt(ProjectRows.nullableInstant(resultSet, "updated_at"))
            .build();
      };

  private final JdbcClient jdbc;
  private final ProjectChangeNotifier notifier;

  public IssueRepository(JdbcClient jdbc, ProjectChangeNotifier notifier) {
    this.jdbc = jdbc;
    this.notifier = notifier;
  }

  public boolean create(Issue issue) {
    boolean changed =
        jdbc.sql(
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
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(issue.getProjectId());
    }
    return changed;
  }

  /** 按 id 读取 Issue；未提交的并发写入不可见，每次调用都是新的语句快照。 */
  public Issue getById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue where id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public Issue lockById(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from project_issue where id = :id for update")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  public Issue getByProjectAndNumber(UUID projectId, long number) {
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

  public List<Issue> listByProjectId(UUID projectId) {
    return jdbc.sql(
            "select "
                + COLUMNS
                + " from project_issue where project_id = :projectId order by number asc")
        .param("projectId", projectId)
        .query(ROW_MAPPER)
        .list();
  }

  public List<Issue> listByProjectIdAndArchived(UUID projectId, boolean archived) {
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

  /** 以版本 CAS 更新状态、阻塞恢复点、暂停门禁与编号/序号分配游标。 */
  public boolean updateById(Issue issue, long expectedVersion) {
    boolean changed =
        jdbc.sql(
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
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(issue.getProjectId());
    }
    return changed;
  }

  public boolean deleteById(UUID id, long expectedVersion) {
    Issue issue = getById(id);
    boolean changed =
        jdbc.sql("delete from project_issue where id = :id and version = :expectedVersion")
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update()
            == 1;
    if (changed) {
      notifier.projectChanged(issue.getProjectId());
    }
    return changed;
  }
}
