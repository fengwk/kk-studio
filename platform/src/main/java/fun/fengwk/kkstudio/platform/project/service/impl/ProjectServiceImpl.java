package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@AllArgsConstructor
@Service
public class ProjectServiceImpl implements ProjectService {

  private final ProjectRepository projectRepository;
  private final ProjectSessionRepository projectSessionRepository;

  @Transactional
  @Override
  public Project createProject(String title, String description, String coordinatorAgentName) {
    String trimmedTitle = ProjectValidationUtils.trimAndValidate(title, "title", 255, true);
    String trimmedCoordinator =
        ProjectValidationUtils.trimAndValidate(
            coordinatorAgentName, "coordinatorAgentName", 128, true);
    ProjectValidationUtils.validateUtf8Bytes(description, "description", 65536, false);

    Project project =
        Project.builder()
            .id(UUID.randomUUID())
            .title(trimmedTitle)
            .description(description != null ? description : "")
            .coordinatorAgentName(trimmedCoordinator)
            .nextIssueNumber(1L)
            .version(0L)
            .archivedAt(null)
            .build();
    boolean created = projectRepository.create(project);
    if (!created) {
      throw new AiValidationException("project", "Failed to create project");
    }
    return projectRepository.getById(project.getId());
  }

  @Transactional
  @Override
  public Project updateProject(
      UUID id,
      long expectedVersion,
      String title,
      String description,
      String coordinatorAgentName) {
    Objects.requireNonNull(id, "id");
    String trimmedTitle = ProjectValidationUtils.trimAndValidate(title, "title", 255, true);
    String trimmedCoordinator =
        ProjectValidationUtils.trimAndValidate(
            coordinatorAgentName, "coordinatorAgentName", 128, true);
    ProjectValidationUtils.validateUtf8Bytes(description, "description", 65536, false);

    Project current = projectRepository.lockById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project", id.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project",
          id.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      throw new AiValidationException("project", "Archived project cannot be modified");
    }
    current.setTitle(trimmedTitle);
    current.setDescription(description != null ? description : "");
    current.setCoordinatorAgentName(trimmedCoordinator);
    boolean updated = projectRepository.updateById(current, expectedVersion);
    if (!updated) {
      Project latest = projectRepository.getById(id);
      throw new AiVersionConflictException(
          "project",
          id.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }
    return projectRepository.getById(id);
  }

  @Transactional
  @Override
  public Project archiveProject(UUID id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Project current = projectRepository.lockById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project", id.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project",
          id.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
    }
    if (current.isArchived()) {
      return current;
    }
    boolean updated = projectRepository.updateArchivedAt(id, Instant.now(), expectedVersion);
    if (!updated) {
      Project latest = projectRepository.getById(id);
      throw new AiVersionConflictException(
          "project",
          id.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }
    return projectRepository.getById(id);
  }

  @Transactional
  @Override
  public Project unarchiveProject(UUID id, long expectedVersion) {
    Objects.requireNonNull(id, "id");
    Project current = projectRepository.lockById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project", id.toString());
    }
    if (current.getVersion() != expectedVersion) {
      throw new AiVersionConflictException(
          "project",
          id.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(current.getVersion()));
    }
    if (!current.isArchived()) {
      return current;
    }
    boolean updated = projectRepository.updateArchivedAt(id, null, expectedVersion);
    if (!updated) {
      Project latest = projectRepository.getById(id);
      throw new AiVersionConflictException(
          "project",
          id.toString(),
          String.valueOf(expectedVersion),
          String.valueOf(latest != null ? latest.getVersion() : -1));
    }
    return projectRepository.getById(id);
  }

  @Override
  public Project getProject(UUID id) {
    Objects.requireNonNull(id, "id");
    Project project = projectRepository.getById(id);
    if (project == null) {
      throw new AiResourceNotFoundException("project", id.toString());
    }
    return project;
  }

  @Override
  public List<Project> listProjects(boolean includeArchived) {
    if (includeArchived) {
      return projectRepository.listAll();
    }
    return projectRepository.listByArchived(false);
  }

  @Override
  public ProjectSession getCoordinatorSession(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");
    return projectSessionRepository.findByProjectId(projectId);
  }

  @Override
  public ProjectSession findProjectSession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return projectSessionRepository.findBySessionId(sessionId);
  }
}
