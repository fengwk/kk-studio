package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.ProjectMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.ProjectDO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlProjectRepository implements ProjectRepository {

  private final ProjectMapper projectMapper;

  @Override
  public boolean create(Project project) {
    return projectMapper.insert(toDO(project)) == 1;
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
  public boolean updateById(Project project, long expectedVersion) {
    return projectMapper.updateById(toDO(project), expectedVersion) == 1;
  }

  @Override
  public boolean updateArchivedAt(UUID id, Instant archivedAt, long expectedVersion) {
    return projectMapper.updateArchivedAt(id, archivedAt, expectedVersion) == 1;
  }

  @Override
  public long allocateNextIssueNumber(UUID projectId) {
    Long allocated = projectMapper.allocateNextIssueNumber(projectId);
    if (allocated == null) {
      throw new IllegalStateException(
          "Failed to allocate next issue number for project " + projectId);
    }
    return allocated;
  }

  @Override
  public List<Project> listAll() {
    return projectMapper.listAll().stream().map(this::toModel).collect(Collectors.toList());
  }

  @Override
  public List<Project> listByArchived(boolean archived) {
    return projectMapper.listByArchived(archived).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public boolean deleteById(UUID id, long expectedVersion) {
    return projectMapper.deleteById(id, expectedVersion) == 1;
  }

  private ProjectDO toDO(Project project) {
    if (project == null) {
      return null;
    }
    ProjectDO target = new ProjectDO();
    target.setId(project.getId());
    target.setTitle(project.getTitle());
    target.setDescription(project.getDescription());
    target.setCoordinatorAgentName(project.getCoordinatorAgentName());
    target.setNextIssueNumber(project.getNextIssueNumber());
    target.setVersion(project.getVersion());
    target.setArchivedAt(project.getArchivedAt());
    target.setCreatedAt(project.getCreatedAt());
    target.setUpdatedAt(project.getUpdatedAt());
    return target;
  }

  private Project toModel(ProjectDO row) {
    if (row == null) {
      return null;
    }
    return Project.builder()
        .id(row.getId())
        .title(row.getTitle())
        .description(row.getDescription())
        .coordinatorAgentName(row.getCoordinatorAgentName())
        .nextIssueNumber(row.getNextIssueNumber() != null ? row.getNextIssueNumber() : 1L)
        .version(row.getVersion() != null ? row.getVersion() : 0L)
        .archivedAt(row.getArchivedAt())
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
