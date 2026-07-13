package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentModelServiceImpl implements AgentModelService {

  private final AgentModelRepository agentModelRepository;
  private final WorkspaceRepository workspaceRepository;
  private final AgentModelConverter agentModelConverter;
  private final AgentModelMutationFactory modelMutationFactory;
  private final AgentModelReferenceResolver referenceResolver;

  @Override
  public Page<AgentModelDTO> pageModels(long workspaceId, PageQuery pageQuery) {
    requireWorkspace(workspaceId);
    return agentModelRepository.page(workspaceId, pageQuery).map(agentModelConverter::convert);
  }

  @Override
  public AgentModelDTO createModel(long workspaceId, AgentModelCreateDTO createDTO) {
    requireWorkspace(workspaceId);
    long providerId = parseId(createDTO == null ? null : createDTO.getProviderId(), "providerId");
    referenceResolver.requireProvider(workspaceId, providerId);
    AgentModel model = modelMutationFactory.newModel(workspaceId, providerId, createDTO);
    referenceResolver.ensureNameAvailable(workspaceId, model.getName());
    if (!agentModelRepository.create(model)) {
      throw new IllegalStateException("create agent model failed");
    }
    return agentModelConverter.convert(agentModelRepository.getByWorkspaceIdAndId(workspaceId, model.getId()));
  }

  @Override
  public AgentModelDTO updateModel(long workspaceId, long id, AgentModelUpdateDTO updateDTO) {
    requireWorkspace(workspaceId);
    AgentModel model = referenceResolver.requireModel(workspaceId, id);
    String currentName = model.getName();
    modelMutationFactory.update(model, updateDTO);
    referenceResolver.ensureNameAvailable(workspaceId, currentName, model.getName());
    if (!agentModelRepository.updateById(model)) {
      throw new IllegalStateException("update agent model failed: " + id);
    }
    return agentModelConverter.convert(agentModelRepository.getByWorkspaceIdAndId(workspaceId, id));
  }

  @Override
  public void deleteModel(long workspaceId, long id) {
    requireWorkspace(workspaceId);
    referenceResolver.requireModel(workspaceId, id);
    referenceResolver.ensureDeletable(workspaceId, id);
    if (!agentModelRepository.deleteByWorkspaceIdAndId(workspaceId, id)) {
      throw new IllegalStateException("delete agent model failed: " + id);
    }
  }

  private void requireWorkspace(long workspaceId) {
    if (workspaceId <= 0 || workspaceRepository.getById(workspaceId) == null) {
      throw new IllegalArgumentException("workspace not found: " + workspaceId);
    }
  }

  private long parseId(String value, String field) {
    try {
      long id = Long.parseLong(value);
      if (id <= 0) {
        throw new NumberFormatException();
      }
      return id;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(field + " must be a positive Snowflake ID", e);
    }
  }
}
