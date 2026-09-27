package fun.fengwk.kkstudio.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.repo.impl.mapper.ProjectMapper;
import fun.fengwk.kkstudio.project.repo.impl.model.ProjectDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlProjectRepository implements ProjectRepository {

  private final ProjectMapper projectMapper;
  private final PostgresqlProjectChangeNotifier notifier;

  @Override
  public boolean create(Project project) {
    boolean changed = projectMapper.insert(toDO(project)) == 1;
    if (changed) {
      notifier.projectChanged(project.getId());
    }
    return changed;
  }

  @Override
  public Project getById(UUID id) {
    return toModel(projectMapper.getById(id));
  }

  @Override
  public Project lockById(UUID id) {
    return toModel(projectMapper.lockById(id));
  }

  @Override
  public Project lockForShare(UUID id) {
    return toModel(projectMapper.lockForShare(id));
  }

  @Override
  public Project lockForKeyShare(UUID id) {
    return toModel(projectMapper.lockForKeyShare(id));
  }

  @Override
  public boolean updateConfiguration(Project project, long expectedVersion) {
    boolean changed = projectMapper.updateConfiguration(toDO(project), expectedVersion) == 1;
    if (changed) {
      notifier.projectChanged(project.getId());
    }
    return changed;
  }

  @Override
  public boolean updateArchivedAt(UUID id, Instant archivedAt, long expectedVersion) {
    boolean changed = projectMapper.updateArchivedAt(id, archivedAt, expectedVersion) == 1;
    if (changed) {
      notifier.projectChanged(id);
    }
    return changed;
  }

  @Override
  public long allocateNextIssueNumber(UUID projectId) {
    Long allocated = projectMapper.allocateNextIssueNumber(projectId);
    if (allocated == null) {
      throw new IllegalStateException("project " + projectId + " disappeared during allocation");
    }
    return allocated;
  }

  @Override
  public List<Project> listAll() {
    return projectMapper.listAll().stream().map(PostgresqlProjectRepository::toModel).toList();
  }

  @Override
  public List<Project> listByArchived(boolean archived) {
    return projectMapper.listByArchived(archived).stream()
        .map(PostgresqlProjectRepository::toModel)
        .toList();
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    boolean changed = projectMapper.deleteById(id, expectedVersion) == 1;
    if (changed) {
      notifier.projectChanged(id);
    }
    return changed;
  }

  private static ProjectDO toDO(Project project) {
    if (project == null) {
      return null;
    }
    ProjectDO row = new ProjectDO();
    row.setId(project.getId());
    row.setTitle(project.getTitle());
    row.setDescription(project.getDescription());
    row.setWorkflowJson(project.getWorkflowJson());
    row.setYoloEnabled(project.isYoloEnabled());
    row.setNextIssueNumber(project.getNextIssueNumber());
    row.setVersion(project.getVersion());
    row.setArchivedAt(project.getArchivedAt());
    row.setCreatedAt(project.getCreatedAt());
    row.setUpdatedAt(project.getUpdatedAt());
    return row;
  }

  private static Project toModel(ProjectDO row) {
    if (row == null) {
      return null;
    }
    return Project.builder()
        .id(row.getId())
        .title(row.getTitle())
        .description(row.getDescription())
        .workflowJson(row.getWorkflowJson())
        .yoloEnabled(row.getYoloEnabled() != null && row.getYoloEnabled())
        .nextIssueNumber(row.getNextIssueNumber() != null ? row.getNextIssueNumber() : 1L)
        .version(row.getVersion() != null ? row.getVersion() : 0L)
        .archivedAt(row.getArchivedAt())
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
