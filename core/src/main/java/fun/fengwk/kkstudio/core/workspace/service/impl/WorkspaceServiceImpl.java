package fun.fengwk.kkstudio.core.workspace.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextWorkspaceId;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.core.workspace.service.WorkspaceService;
import fun.fengwk.kkstudio.core.workspace.service.converter.WorkspaceConverter;
import fun.fengwk.kkstudio.core.workspace.service.model.Workspace;
import fun.fengwk.kkstudio.harness.runtime.permission.WorkspaceToolSettingsCodec;
import fun.fengwk.kkstudio.share.model.WorkspaceCreateDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceDTO;
import fun.fengwk.kkstudio.share.model.WorkspaceUpdateDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class WorkspaceServiceImpl implements WorkspaceService {

  private static final String EMPTY_OBJECT_JSON = "{}";

  private final WorkspaceRepository workspaceRepository;
  private final WorkspaceConverter workspaceConverter;
  private final AgentEditableSupport editableSupport;
  private final WorkspaceToolSettingsCodec settingsCodec;

  @Override
  public Page<WorkspaceDTO> pageWorkspaces(PageQuery pageQuery) {
    return workspaceRepository.page(pageQuery).map(workspaceConverter::convert);
  }

  @Override
  public WorkspaceDTO getWorkspace(long id) {
    return workspaceConverter.convert(requireWorkspace(id));
  }

  @Override
  public WorkspaceDTO createWorkspace(WorkspaceCreateDTO createDTO) {
    String name = requireName(createDTO == null ? null : createDTO.getName());
    ensureNameAvailable(name);
    Workspace workspace = new Workspace();
    workspace.setId(nextWorkspaceId());
    workspace.setName(name);
    workspace.setSettingsJson(normalizeSettings(createDTO.getSettingsJson()));
    if (!workspaceRepository.create(workspace)) {
      throw new IllegalStateException("create workspace failed");
    }
    return workspaceConverter.convert(workspaceRepository.getById(workspace.getId()));
  }

  @Override
  public WorkspaceDTO updateWorkspace(long id, WorkspaceUpdateDTO updateDTO) {
    Workspace workspace = requireWorkspace(id);
    if (updateDTO == null) {
      throw new IllegalArgumentException("workspace body must not be null");
    }
    String name =
        editableSupport.firstNonBlank(
            editableSupport.trimToNull(updateDTO.getName()), workspace.getName());
    if (!workspace.getName().equals(name)) {
      ensureNameAvailable(name);
    }
    workspace.setName(name);
    if (updateDTO.getSettingsJson() != null) {
      workspace.setSettingsJson(normalizeSettings(updateDTO.getSettingsJson()));
    }
    if (!workspaceRepository.updateById(workspace)) {
      throw new IllegalStateException("update workspace failed: " + id);
    }
    return workspaceConverter.convert(workspaceRepository.getById(id));
  }

  @Override
  public void deleteWorkspace(long id) {
    requireWorkspace(id);
    if (workspaceRepository.hasResources(id)) {
      throw new IllegalStateException("workspace contains configuration resources: " + id);
    }
    if (!workspaceRepository.deleteById(id)) {
      throw new IllegalStateException("delete workspace failed: " + id);
    }
  }

  private Workspace requireWorkspace(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("workspace id must be positive");
    }
    Workspace workspace = workspaceRepository.getById(id);
    if (workspace == null) {
      throw new IllegalArgumentException("workspace not found: " + id);
    }
    return workspace;
  }

  private void ensureNameAvailable(String name) {
    if (workspaceRepository.getByName(name) != null) {
      throw new IllegalArgumentException("workspace name already exists: " + name);
    }
  }

  private String requireName(String name) {
    String result = editableSupport.trimToNull(name);
    if (result == null) {
      throw new IllegalArgumentException("workspace name must not be blank");
    }
    return result;
  }

  private String normalizeSettings(String settingsJson) {
    String result = editableSupport.firstNonBlank(settingsJson, EMPTY_OBJECT_JSON);
    return settingsCodec.canonicalize(result);
  }
}
