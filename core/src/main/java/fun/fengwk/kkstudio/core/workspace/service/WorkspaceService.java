package fun.fengwk.kkstudio.core.workspace.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceUpdateDTO;

/**
 * @author fengwk
 */
public interface WorkspaceService {

  Page<WorkspaceDTO> pageWorkspaces(PageQuery pageQuery);

  WorkspaceDTO createWorkspace(WorkspaceCreateDTO createDTO);

  WorkspaceDTO updateWorkspace(long id, WorkspaceUpdateDTO updateDTO);

  void deleteWorkspace(long id);
}
