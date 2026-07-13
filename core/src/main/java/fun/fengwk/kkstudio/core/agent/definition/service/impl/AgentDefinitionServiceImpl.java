package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.workspace.repo.WorkspaceRepository;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final WorkspaceRepository workspaceRepository;
  private final AgentDefinitionConverter agentDefinitionConverter;
  private final AgentDefinitionMutationFactory definitionMutationFactory;
  private final AgentDefinitionReferenceResolver referenceResolver;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(long workspaceId, PageQuery pageQuery) {
    requireWorkspace(workspaceId);
    return agentDefinitionRepository.page(workspaceId, pageQuery).map(agentDefinitionConverter::convert);
  }

  @Override
  public AgentDefinitionDTO createAgent(long workspaceId, AgentDefinitionCreateDTO createDTO) {
    requireWorkspace(workspaceId);
    long modelId = parseModelId(createDTO == null ? null : createDTO.getModelId());
    referenceResolver.requireModel(workspaceId, modelId);
    AgentDefinition definition = definitionMutationFactory.newAgent(workspaceId, modelId, createDTO);
    referenceResolver.ensureNameAvailable(workspaceId, definition.getName());
    if (!agentDefinitionRepository.create(definition)) {
      throw new IllegalStateException("create agent definition failed");
    }
    return agentDefinitionConverter.convert(
        agentDefinitionRepository.getByWorkspaceIdAndId(workspaceId, definition.getId()));
  }

  @Override
  public AgentDefinitionDTO updateAgent(long workspaceId, long id, AgentDefinitionUpdateDTO updateDTO) {
    requireWorkspace(workspaceId);
    AgentDefinition definition = referenceResolver.requireAgent(workspaceId, id);
    long modelId =
        updateDTO == null || updateDTO.getModelId() == null || updateDTO.getModelId().isBlank()
            ? definition.getModelId()
            : parseModelId(updateDTO.getModelId());
    referenceResolver.requireModel(workspaceId, modelId);
    String currentName = definition.getName();
    definitionMutationFactory.update(definition, updateDTO);
    definition.setModelId(modelId);
    referenceResolver.ensureNameAvailable(workspaceId, currentName, definition.getName());
    if (!agentDefinitionRepository.updateById(definition)) {
      throw new IllegalStateException("update agent definition failed: " + id);
    }
    return agentDefinitionConverter.convert(agentDefinitionRepository.getByWorkspaceIdAndId(workspaceId, id));
  }

  @Override
  public void deleteAgent(long workspaceId, long id) {
    requireWorkspace(workspaceId);
    referenceResolver.requireAgent(workspaceId, id);
    if (!agentDefinitionRepository.deleteByWorkspaceIdAndId(workspaceId, id)) {
      throw new IllegalStateException("delete agent definition failed: " + id);
    }
  }

  private void requireWorkspace(long workspaceId) {
    if (workspaceId <= 0 || workspaceRepository.getById(workspaceId) == null) {
      throw new IllegalArgumentException("workspace not found: " + workspaceId);
    }
  }

  private long parseModelId(String value) {
    try {
      long id = Long.parseLong(value);
      if (id <= 0) {
        throw new NumberFormatException();
      }
      return id;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("modelId must be a positive Snowflake ID", e);
    }
  }
}
