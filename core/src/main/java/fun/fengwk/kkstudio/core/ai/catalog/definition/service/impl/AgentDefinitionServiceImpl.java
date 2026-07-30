package fun.fengwk.kkstudio.core.ai.catalog.definition.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.core.ai.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.core.ai.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.core.persistence.PostgresqlIntegrityViolationClassifier;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;

/** Global Agent definition CRUD. */
@AllArgsConstructor
@Service
public class AgentDefinitionServiceImpl implements AgentDefinitionService {

  private static final String RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";

  private final AgentDefinitionRepository agentDefinitionRepository;
  private final AgentDefinitionConverter agentDefinitionConverter;
  private final AgentDefinitionMutationFactory definitionMutationFactory;
  private final AgentDefinitionReferenceResolver referenceResolver;
  private final AgentModelDefaultVariantResolver variantResolver;
  private final AgentDefinitionLiveCapabilityValidator liveCapabilityValidator;
  private final AgentDefinitionConfigCodec configCodec;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository.page(pageQuery).map(agentDefinitionConverter::convert);
  }

  @Override
  @Transactional
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    long modelId = parseModelId(createDTO == null ? null : createDTO.getModelId());
    referenceResolver.requireModel(modelId);
    AgentDefinition definition = definitionMutationFactory.newAgent(modelId, createDTO);
    referenceResolver.ensureNameAvailable(definition.getName());
    validateVariant(modelId, definition.getVariant());
    validateLiveCapabilities(configCodec.decode(definition.getConfigJson()));
    try {
      if (!agentDefinitionRepository.create(definition)) {
        throw new IllegalStateException("create agent definition failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent definition name already exists: " + definition.getName(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(
            MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + modelId, error);
      }
      throw error;
    }
    AgentDefinition loaded = agentDefinitionRepository.getById(definition.getId());
    return agentDefinitionConverter.convert(loaded);
  }

  @Override
  @Transactional
  public AgentDefinitionDTO updateAgent(long id, AgentDefinitionUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentDefinition definition = referenceResolver.requireAgent(id);
    ensureExpectedVersion(definition, id, rawExpected, expected);
    long modelId = parseModelId(updateDTO == null ? null : updateDTO.getModelId());
    referenceResolver.requireModel(modelId);
    String currentName = definition.getName();
    definitionMutationFactory.update(definition, updateDTO);
    definition.setModelId(modelId);
    referenceResolver.ensureNameAvailable(currentName, definition.getName());
    validateVariant(modelId, definition.getVariant());
    validateLiveCapabilities(configCodec.decode(definition.getConfigJson()));
    try {
      if (!agentDefinitionRepository.updateById(definition, expected)) {
        AgentDefinition reread = agentDefinitionRepository.getById(id);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
        }
        throw new AiVersionConflictException(
            RESOURCE, Long.toString(id), rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent definition name already exists: " + definition.getName(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(
            MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + modelId, error);
      }
      throw error;
    }
    AgentDefinition reloaded = agentDefinitionRepository.getById(id);
    return agentDefinitionConverter.convert(reloaded);
  }

  @Override
  @Transactional
  public void deleteAgent(long id, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentDefinition definition = referenceResolver.requireAgent(id);
    ensureExpectedVersion(definition, id, expectedVersion, expected);
    if (!agentDefinitionRepository.deleteById(id, expected)) {
      AgentDefinition reread = agentDefinitionRepository.getById(id);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
      }
      throw new AiVersionConflictException(
          RESOURCE,
          Long.toString(id),
          expectedVersion,
          CatalogVersions.format(reread.getVersion()));
    }
  }

  private static void ensureExpectedVersion(
      AgentDefinition definition, long id, String expectedVersion, long expected) {
    if (definition.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE,
          Long.toString(id),
          expectedVersion,
          CatalogVersions.format(definition.getVersion()));
    }
  }

  private long parseModelId(String value) {
    if (value == null) {
      throw new AiValidationException(RESOURCE, "modelId must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new AiValidationException(RESOURCE, "modelId must not be blank");
    }
    if (!trimmed.matches("^[1-9][0-9]*$")) {
      throw new AiValidationException(
          RESOURCE, "modelId must be an unsigned positive decimal: " + value);
    }
    try {
      return Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new AiValidationException(RESOURCE, "modelId exceeds long range: " + value, error);
    }
  }

  private void validateVariant(long modelId, String variant) {
    try {
      variantResolver.resolve(modelId, variant);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  private void validateLiveCapabilities(AgentDefinitionConfigDTO config) {
    try {
      liveCapabilityValidator.validate(config);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }
}
