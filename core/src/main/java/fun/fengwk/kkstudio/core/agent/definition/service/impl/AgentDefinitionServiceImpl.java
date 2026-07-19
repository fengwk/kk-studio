package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.share.model.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;

/** Global Agent definition CRUD. */
@AllArgsConstructor
@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConverter agentDefinitionConverter;
  private final AgentDefinitionMutationFactory definitionMutationFactory;
  private final AgentDefinitionReferenceResolver referenceResolver;
  private final AgentDefinitionLiveCapabilityValidator liveCapabilityValidator;
  private final ObjectMapper objectMapper;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository.page(pageQuery).map(agentDefinitionConverter::convert);
  }

  @Override
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    long modelId = parseModelId(createDTO == null ? null : createDTO.getModelId());
    referenceResolver.requireModel(modelId);
    AgentDefinition definition = definitionMutationFactory.newAgent(modelId, createDTO);
    referenceResolver.ensureNameAvailable(definition.getName());
    liveCapabilityValidator.validate(readConfig(definition.getConfigJson()));
    if (!agentDefinitionRepository.create(definition)) {
      throw new IllegalStateException("create agent definition failed");
    }
    return agentDefinitionConverter.convert(agentDefinitionRepository.getById(definition.getId()));
  }

  @Override
  public AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO) {
    AgentDefinition definition = referenceResolver.requireAgent(id);
    long modelId =
        updateDTO == null || updateDTO.getModelId() == null || updateDTO.getModelId().isBlank()
            ? definition.getModelId()
            : parseModelId(updateDTO.getModelId());
    referenceResolver.requireModel(modelId);
    String currentName = definition.getName();
    definitionMutationFactory.update(definition, updateDTO);
    definition.setModelId(modelId);
    referenceResolver.ensureNameAvailable(currentName, definition.getName());
    liveCapabilityValidator.validate(readConfig(definition.getConfigJson()));
    if (!agentDefinitionRepository.updateById(definition)) {
      throw new IllegalStateException("update agent definition failed: " + id);
    }
    return agentDefinitionConverter.convert(agentDefinitionRepository.getById(id));
  }

  @Override
  public void deleteAgent(long id) {
    referenceResolver.requireAgent(id);
    if (!agentDefinitionRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent definition failed: " + id);
    }
  }

  private AgentDefinitionConfigDTO readConfig(String configJson) {
    try {
      return objectMapper.readValue(configJson, AgentDefinitionConfigDTO.class);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("agent definition config is invalid", error);
    }
  }

  private long parseModelId(String value) {
    try {
      long id = Long.parseLong(value);
      if (id <= 0) {
        throw new NumberFormatException();
      }
      return id;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("modelId must be a positive Snowflake ID", error);
    }
  }
}
