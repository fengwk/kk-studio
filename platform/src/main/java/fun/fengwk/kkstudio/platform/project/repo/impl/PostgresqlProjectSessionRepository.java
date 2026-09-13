package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.ProjectSessionMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.ProjectSessionDO;

import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlProjectSessionRepository implements ProjectSessionRepository {

  private final ProjectSessionMapper projectSessionMapper;

  @Override
  public boolean bindSession(UUID projectId, UUID sessionId) {
    return projectSessionMapper.insert(projectId, sessionId) == 1;
  }

  @Override
  public ProjectSession findByProjectId(UUID projectId) {
    return toModel(projectSessionMapper.findByProjectId(projectId));
  }

  @Override
  public ProjectSession findBySessionId(UUID sessionId) {
    return toModel(projectSessionMapper.findBySessionId(sessionId));
  }

  @Override
  public boolean deleteByProjectId(UUID projectId) {
    return projectSessionMapper.deleteByProjectId(projectId) == 1;
  }

  private ProjectSession toModel(ProjectSessionDO row) {
    if (row == null) {
      return null;
    }
    return ProjectSession.builder()
        .projectId(row.getProjectId())
        .sessionId(row.getSessionId())
        .createdAt(row.getCreatedAt())
        .build();
  }
}
