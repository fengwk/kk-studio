package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.ProjectSession;

import java.util.UUID;

public interface ProjectSessionRepository {

  boolean bindSession(UUID projectId, UUID sessionId);

  ProjectSession findByProjectId(UUID projectId);

  ProjectSession findBySessionId(UUID sessionId);

  boolean deleteByProjectId(UUID projectId);
}
