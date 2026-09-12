package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;

import java.util.List;
import java.util.UUID;

public interface ProjectService {

  Project createProject(String title, String description, String coordinatorAgentName);

  Project updateProject(
      UUID id, long expectedVersion, String title, String description, String coordinatorAgentName);

  Project archiveProject(UUID id, long expectedVersion);

  Project unarchiveProject(UUID id, long expectedVersion);

  Project getProject(UUID id);

  List<Project> listProjects(boolean includeArchived);

  void bindCoordinatorSession(UUID projectId, UUID sessionId);

  ProjectSession getCoordinatorSession(UUID projectId);

  ProjectSession findProjectSession(UUID sessionId);
}
