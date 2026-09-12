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
    if (title == null || title.isBlank()) {
      throw new AiValidationException("project", "Project title must not be blank");
    }
    if (coordinatorAgentName == null || coordinatorAgentName.isBlank()) {
      throw new AiValidationException("project", "Coordinator agent name must not be blank");
    }
    Project project =
        Project.builder()
            .id(UUID.randomUUID())
            .title(title.trim())
            .description(description != null ? description : "")
            .coordinatorAgentName(coordinatorAgentName.trim())
            .nextIssueNumber(1L)
            .version(0L)
            .archivedAt(null)
            .build();
    projectRepository.create(project);
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
    if (title == null || title.isBlank()) {
      throw new AiValidationException("project", "Project title must not be blank");
    }
    if (coordinatorAgentName == null || coordinatorAgentName.isBlank()) {
      throw new AiValidationException("project", "Coordinator agent name must not be blank");
    }
    Project current = projectRepository.getById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project", id.toString());
    }
    if (current.isArchived()) {
      throw new AiValidationException("project", "Archived project cannot be modified");
    }
    current.setTitle(title.trim());
    current.setDescription(description != null ? description : "");
    current.setCoordinatorAgentName(coordinatorAgentName.trim());
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
    Project current = projectRepository.getById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project", id.toString());
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
    Project current = projectRepository.getById(id);
    if (current == null) {
      throw new AiResourceNotFoundException("project", id.toString());
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

  @Transactional
  @Override
  public void bindCoordinatorSession(UUID projectId, UUID sessionId) {
    Objects.requireNonNull(projectId, "projectId");
    Objects.requireNonNull(sessionId, "sessionId");
    Project project = projectRepository.getById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project", projectId.toString());
    }
    projectSessionRepository.bindSession(projectId, sessionId);
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
