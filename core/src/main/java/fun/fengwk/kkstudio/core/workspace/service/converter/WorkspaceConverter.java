package fun.fengwk.kkstudio.core.workspace.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.workspace.service.model.Workspace;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;

/**
 * @author fengwk
 */
@Component
public class WorkspaceConverter {

  public WorkspaceDTO convert(Workspace workspace) {
    if (workspace == null) {
      return null;
    }
    WorkspaceDTO dto = new WorkspaceDTO();
    dto.setId(Long.toString(workspace.getId()));
    dto.setName(workspace.getName());
    dto.setSettingsJson(workspace.getSettingsJson());
    dto.setVersion(workspace.getVersion());
    dto.setCreateTime(workspace.getCreateTime());
    dto.setUpdateTime(workspace.getUpdateTime());
    return dto;
  }
}
