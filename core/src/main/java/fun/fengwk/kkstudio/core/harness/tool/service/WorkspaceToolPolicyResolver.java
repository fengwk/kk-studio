package fun.fengwk.kkstudio.core.harness.tool.service;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.core.workspace.service.model.Workspace;
import fun.fengwk.kkstudio.harness.runtime.permission.WorkspaceToolSettings;
import fun.fengwk.kkstudio.harness.runtime.permission.WorkspaceToolSettingsCodec;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 在事务锁内解析 Workspace ordered rules 与 Root Session 动态 YOLO。 */
@Component
public class WorkspaceToolPolicyResolver {
  private final WorkspaceRepository workspaceRepository;
  private final HarnessSessionMapper sessionMapper;
  private final WorkspaceToolSettingsCodec settingsCodec;

  public WorkspaceToolPolicyResolver(
      WorkspaceRepository workspaceRepository,
      HarnessSessionMapper sessionMapper,
      WorkspaceToolSettingsCodec settingsCodec) {
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.settingsCodec = Objects.requireNonNull(settingsCodec, "settingsCodec");
  }

  public ResolvedPolicy resolve(HarnessSessionDO session) {
    Workspace workspace = workspaceRepository.getById(session.getWorkspaceId());
    if (workspace == null) {
      throw new IllegalStateException(
          "session workspace does not exist: " + session.getWorkspaceId());
    }
    HarnessSessionDO root =
        session.getRootSessionId().equals(session.getId())
            ? session
            : sessionMapper.findForUpdate(session.getRootSessionId());
    if (root == null
        || root.getParentSessionId() != null
        || !Objects.equals(root.getRootSessionId(), root.getId())
        || !root.getWorkspaceId().equals(session.getWorkspaceId())) {
      throw new IllegalStateException("invalid root session for permission resolution");
    }
    WorkspaceToolSettings settings = settingsCodec.decode(workspace.getSettingsJson());
    return new ResolvedPolicy(settings, Boolean.TRUE.equals(root.getYoloEnabled()));
  }

  public record ResolvedPolicy(WorkspaceToolSettings settings, boolean yoloEnabled) {}
}
