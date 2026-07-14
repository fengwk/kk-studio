package fun.fengwk.kkstudio.core.harness.tool.service;

import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.core.workspace.service.model.Workspace;
import fun.fengwk.kkstudio.harness.runtime.permission.WorkspaceToolSettingsCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionYoloResolver;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Root Session 创建时从 Workspace canonical settings 继承 defaultYolo。 */
@Component
public class WorkspaceDefaultYoloResolver implements SessionYoloResolver {
  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceToolSettingsCodec settingsCodec;

  public WorkspaceDefaultYoloResolver(
      WorkspaceRepository workspaceRepository, WorkspaceToolSettingsCodec settingsCodec) {
    this.workspaceRepository = Objects.requireNonNull(workspaceRepository, "workspaceRepository");
    this.settingsCodec = Objects.requireNonNull(settingsCodec, "settingsCodec");
  }

  @Override
  public boolean defaultYolo(long workspaceId) {
    Workspace workspace = workspaceRepository.getById(workspaceId);
    if (workspace == null) {
      throw new IllegalArgumentException("workspace not found: " + workspaceId);
    }
    return settingsCodec.decode(workspace.getSettingsJson()).defaultYolo();
  }
}
