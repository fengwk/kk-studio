package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.Project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code project} 聚合根持久化端口。
 *
 * <p>workflow 整体保存为严格 JSON 文本，通过行版本 CAS 更新；Issue 编号由 {@code next_issue_number} 单调分配。配置更新与归档都在
 * Project 行锁与版本检查下进行，影响执行的结构调整由服务要求项目无活动主 Run。
 */
public interface ProjectRepository {

  boolean create(Project project);

  Project getById(UUID id);

  Project lockById(UUID id);

  Project lockForShare(UUID id);

  Project lockForKeyShare(UUID id);

  /** 以版本 CAS 更新标题、描述、workflow JSON 与 YOLO 策略。 */
  boolean updateConfiguration(Project project, long expectedVersion);

  boolean updateArchivedAt(UUID id, Instant archivedAt, long expectedVersion);

  /** 原子分配并返回下一个 Issue 编号（推进 {@code next_issue_number}）。 */
  long allocateNextIssueNumber(UUID projectId);

  List<Project> listAll();

  List<Project> listByArchived(boolean archived);

  boolean deleteById(UUID id, long expectedVersion);
}
