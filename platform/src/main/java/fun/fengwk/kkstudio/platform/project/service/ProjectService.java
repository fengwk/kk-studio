package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.Project;

import java.util.List;
import java.util.UUID;

public interface ProjectService {

  Project createProject(
      String title, String description, boolean yoloEnabled, int maxReviewRejections);

  Project updateProject(
      UUID id,
      long expectedVersion,
      String title,
      String description,
      Boolean yoloEnabled,
      Integer maxReviewRejections);

  Project archiveProject(UUID id, long expectedVersion);

  Project unarchiveProject(UUID id, long expectedVersion);

  void deleteProject(UUID id, long expectedVersion);

  Project getProject(UUID id);

  List<Project> listProjects(boolean includeArchived);
}
