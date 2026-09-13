package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.Project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProjectRepository {

  boolean create(Project project);

  Project getById(UUID id);

  Project lockById(UUID id);

  Project lockForShare(UUID id);

  Project lockForKeyShare(UUID id);

  boolean updateById(Project project, long expectedVersion);

  boolean updateArchivedAt(UUID id, Instant archivedAt, long expectedVersion);

  long allocateNextIssueNumber(UUID projectId);

  List<Project> listAll();

  List<Project> listByArchived(boolean archived);

  boolean deleteById(UUID id, long expectedVersion);
}
