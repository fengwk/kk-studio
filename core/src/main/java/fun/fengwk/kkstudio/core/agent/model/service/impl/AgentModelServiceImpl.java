package fun.fengwk.kkstudio.core.agent.model.service.impl;

import static fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator.nextModelId;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import java.io.IOException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.agent.provider.repo.AgentProviderRepository;
import fun.fengwk.kkstudio.core.agent.provider.service.model.AgentProvider;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;
import java.io.IOException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class AgentModelServiceImpl implements AgentModelService {

  private static final String DEFAULT_VARIANT = "default";
  private static final String DEFAULT_VARIANTS_JSON = "[{\"name\":\"default\"}]";

  private final AgentModelRepository agentModelRepository;
  private final AgentProviderRepository agentProviderRepository;
  private final AgentModelConverter agentModelConverter;
  private final ObjectMapper objectMapper;

  @Override
  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return agentModelRepository
        .page(pageQuery)
        .map(
            model ->
                agentModelConverter.convert(
                    model, agentProviderRepository.getById(model.getProviderId())));
  }

  @Override
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    validateEditable(createDTO, false);
    if (createDTO.getProvider() == null || createDTO.getProvider().isBlank()) {
      throw new IllegalArgumentException("agent model provider must not be blank");
    }
    AgentProvider provider = agentProviderRepository.getByName(createDTO.getProvider().trim());
    if (provider == null) {
      throw new IllegalArgumentException("agent provider not found: " + createDTO.getProvider());
    }
    if (agentModelRepository.getByProviderIdAndName(provider.getId(), createDTO.getName().trim())
        != null) {
      throw new IllegalArgumentException("agent model name already exists: " + createDTO.getName());
    }
    AgentModel model = new AgentModel();
    model.setId(nextModelId());
    model.setProviderId(provider.getId());
    model.setName(createDTO.getName().trim());
    applyEditable(model, createDTO);
    if (!agentModelRepository.create(model)) {
      throw new IllegalStateException("create agent model failed");
    }
    return agentModelConverter.convert(agentModelRepository.getById(model.getId()), provider);
  }

  @Override
  public AgentModelDTO updateModel(long id, AgentModelUpdateDTO updateDTO) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent model id must be positive");
    }
    validateEditable(updateDTO, false);
    AgentModel existing = agentModelRepository.getById(id);
    if (existing == null) {
      throw new IllegalArgumentException("agent model not found: " + id);
    }
    AgentProvider provider = agentProviderRepository.getById(existing.getProviderId());
    String newName =
        updateDTO.getName() == null || updateDTO.getName().isBlank()
            ? existing.getName()
            : updateDTO.getName().trim();
    if (!existing.getName().equals(newName)
        && agentModelRepository.getByProviderIdAndName(existing.getProviderId(), newName) != null) {
      throw new IllegalArgumentException("agent model name already exists: " + newName);
    }
    existing.setName(newName);
    applyEditable(existing, updateDTO);
    if (!agentModelRepository.updateById(existing)) {
      throw new IllegalStateException("update agent model failed: " + id);
    }
    return agentModelConverter.convert(agentModelRepository.getById(id), provider);
  }

  @Override
  public void deleteModel(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("agent model id must be positive");
    }
    if (agentModelRepository.getById(id) == null) {
      throw new IllegalArgumentException("agent model not found: " + id);
    }
    if (agentModelRepository.hasAgents(id)) {
      throw new IllegalStateException("agent model in use by agents: " + id);
    }
    if (!agentModelRepository.deleteById(id)) {
      throw new IllegalStateException("delete agent model failed: " + id);
    }
  }

  private void applyEditable(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    model.setDescription(trimToNull(properties.getDescription()));
    model.setCapabilitiesJson(trimToNull(properties.getCapabilitiesJson()));
    model.setLimitJson(trimToNull(properties.getLimitJson()));
    model.setPricingJson(trimToNull(properties.getPricingJson()));
    model.setDefaultVariant(firstNonBlank(properties.getDefaultVariant(), DEFAULT_VARIANT));
    model.setVariantsJson(firstNonBlank(properties.getVariantsJson(), DEFAULT_VARIANTS_JSON));
  }

  private void validateEditable(AgentModelEditablePropertiesDTO properties, boolean requireKey) {
    if (properties == null) {
      throw new IllegalArgumentException("agent model body must not be null");
    }
    if (requireKey || (properties.getName() != null && !properties.getName().isBlank())) {
      if (properties.getName() == null || properties.getName().isBlank()) {
        throw new IllegalArgumentException("agent model name must not be blank");
      }
    }
    validateJsonObjectOrNull(properties.getCapabilitiesJson(), "capabilitiesJson");
    validateJsonObjectOrNull(properties.getLimitJson(), "limitJson");
    validateJsonObjectOrNull(properties.getPricingJson(), "pricingJson");
    validateJsonArray(
        firstNonBlank(properties.getVariantsJson(), DEFAULT_VARIANTS_JSON), "variantsJson");
  }

  private void validateJsonObjectOrNull(String json, String fieldName) {
    String trimmed = trimToNull(json);
    if (trimmed == null) {
      return;
    }
    try {
      if (!objectMapper.readTree(trimmed).isObject()) {
        throw new IllegalArgumentException(fieldName + " must be a JSON object");
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(fieldName + " must be valid JSON", e);
    }
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
