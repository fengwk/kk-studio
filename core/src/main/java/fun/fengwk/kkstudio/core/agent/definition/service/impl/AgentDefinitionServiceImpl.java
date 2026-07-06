package fun.fengwk.kkstudio.core.agent.definition.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextAgentId;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import java.io.IOException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.agent.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.agent.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.agent.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentDefinitionUpdateDTO;
import java.io.IOException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

  private static final String DEFAULT_VARIANT = "default";
  private static final String EMPTY_ARRAY_JSON = "[]";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentModelRepository agentModelRepository;
  private final AgentDefinitionConverter agentDefinitionConverter;
  private final ObjectMapper objectMapper;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository
        .page(pageQuery)
        .map(
            agent ->
                agentDefinitionConverter.convert(
                    agent,
                    agentProviderRepository.getById(agent.getDefaultProviderId()),
                    agentModelRepository.getById(agent.getDefaultModelId())));
  }

  @Override
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    validateEditable(createDTO, true);
    if (agentDefinitionRepository.getByName(createDTO.getName().trim()) != null) {
      throw new IllegalArgumentException(
          "agent definition name already exists: " + createDTO.getName());
    }
    AgentProvider provider =
        agentProviderRepository.getByName(createDTO.getDefaultProvider().trim());
    if (provider == null) {
      throw new IllegalArgumentException(
          "agent provider not found: " + createDTO.getDefaultProvider());
    }
    AgentModel model =
        agentModelRepository.getByProviderIdAndName(
            provider.getId(), createDTO.getDefaultModel().trim());
    if (model == null) {
      throw new IllegalArgumentException(
          "agent model not found: "
              + createDTO.getDefaultProvider()
              + "/"
              + createDTO.getDefaultModel());
    }
    AgentDefinition agent = new AgentDefinition();
    agent.setId(nextAgentId());
    agent.setName(createDTO.getName().trim());
    agent.setDefaultProviderId(provider.getId());
    agent.setDefaultModelId(model.getId());
    applyEditable(agent, createDTO);
    if (!agentDefinitionRepository.create(agent)) {
      throw new IllegalStateException("create agent definition failed");
    }
    return agentDefinitionConverter.convert(
        agentDefinitionRepository.getById(agent.getId()), provider, model);
  }

  @Override
  public AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent id must be positive");
    }
    validateEditable(updateDTO, false);
    AgentDefinition existing = agentDefinitionRepository.getById(id);
    if (existing == null) {
      throw new IllegalArgumentException("agent definition not found: " + id);
    }
    String newName =
        updateDTO.getName() == null || updateDTO.getName().isBlank()
            ? existing.getName()
            : updateDTO.getName().trim();
    if (!existing.getName().equals(newName)
        && agentDefinitionRepository.getByName(newName) != null) {
      throw new IllegalArgumentException("agent definition name already exists: " + newName);
    }
    AgentProvider provider =
        agentProviderRepository.getByName(updateDTO.getDefaultProvider().trim());
    if (provider == null) {
      throw new IllegalArgumentException(
          "agent provider not found: " + updateDTO.getDefaultProvider());
    }
    AgentModel model =
        agentModelRepository.getByProviderIdAndName(
            provider.getId(), updateDTO.getDefaultModel().trim());
    if (model == null) {
      throw new IllegalArgumentException(
          "agent model not found: "
              + updateDTO.getDefaultProvider()
              + "/"
              + updateDTO.getDefaultModel());
    }
    existing.setName(newName);
    existing.setDefaultProviderId(provider.getId());
    existing.setDefaultModelId(model.getId());
    applyEditable(existing, updateDTO);
    if (!agentDefinitionRepository.updateById(existing)) {
      throw new IllegalStateException("update agent definition failed: " + id);
    }
    return agentDefinitionConverter.convert(agentDefinitionRepository.getById(id), provider, model);
  }

  @Override
  public void deleteAgent(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent id must be positive");
    }
    if (agentDefinitionRepository.getById(id) == null) {
      throw new IllegalArgumentException("agent definition not found: " + id);
    }
    if (!agentDefinitionRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent definition failed: " + id);
    }
  }

  private void applyEditable(
      AgentDefinition agent, AgentDefinitionEditablePropertiesDTO properties) {
    agent.setDescription(trimToNull(properties.getDescription()));
    agent.setSystemPrompt(trimToNull(properties.getSystemPrompt()));
    agent.setDefaultVariant(firstNonBlank(properties.getDefaultVariant(), DEFAULT_VARIANT));
    agent.setToolsJson(firstNonBlank(properties.getToolsJson(), EMPTY_ARRAY_JSON));
    agent.setSubagentsJson(firstNonBlank(properties.getSubagentsJson(), EMPTY_ARRAY_JSON));
    agent.setSkillsJson(firstNonBlank(properties.getSkillsJson(), EMPTY_ARRAY_JSON));
  }

  private void validateEditable(
      AgentDefinitionEditablePropertiesDTO properties, boolean requireName) {
    if (properties == null) {
      throw new IllegalArgumentException("agent definition body must not be null");
    }
    if (requireName && (properties.getName() == null || properties.getName().isBlank())) {
      throw new IllegalArgumentException("agent name must not be blank");
    }
    if (properties.getDefaultProvider() == null || properties.getDefaultProvider().isBlank()) {
      throw new IllegalArgumentException("agent defaultProvider must not be blank");
    }
    if (properties.getDefaultModel() == null || properties.getDefaultModel().isBlank()) {
      throw new IllegalArgumentException("agent defaultModel must not be blank");
    }
    validateJsonArray(firstNonBlank(properties.getToolsJson(), EMPTY_ARRAY_JSON), "toolsJson");
    validateJsonArray(
        firstNonBlank(properties.getSubagentsJson(), EMPTY_ARRAY_JSON), "subagentsJson");
    validateJsonArray(firstNonBlank(properties.getSkillsJson(), EMPTY_ARRAY_JSON), "skillsJson");
  }

  private void validateJsonArray(String json, String fieldName) {
    try {
      if (!objectMapper.readTree(json).isArray()) {
        throw new IllegalArgumentException(fieldName + " must be a JSON array");
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(fieldName + " must be valid JSON", e);
    }
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      String trimmed = trimToNull(value);
      if (trimmed != null) {
        return trimmed;
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
