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
import fun.fengwk.kkstudio.share.ai.catalog.ModelRef;

/** 全局 Agent definition CRUD。 */
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
  private final AgentDefinitionConfigValidator configValidator;
  private final AgentDefinitionConfigCodec configCodec;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository.page(pageQuery).map(agentDefinitionConverter::convert);
  }

  @Override
  @Transactional
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    ModelRef modelRef = parseModelRef(createDTO == null ? null : createDTO.getModel());
    referenceResolver.requireModelForUpdate(modelRef.providerName(), modelRef.modelName());
    String name = createDTO == null ? null : createDTO.getName();
    AgentDefinition definition = definitionMutationFactory.newAgent(name, createDTO);
    validateVariant(modelRef, definition.getVariant());
    AgentDefinitionConfigDTO config = configCodec.decode(definition.getConfigJson());
    validateConfig(config);
    referenceResolver.requireSubagentsForUpdate(config.getSubagents());
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
            MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + modelRef, error);
      }
      throw error;
    }
    AgentDefinition loaded = agentDefinitionRepository.getByName(definition.getName());
    return agentDefinitionConverter.convert(loaded);
  }

  @Override
  @Transactional
  public AgentDefinitionDTO updateAgent(String name, AgentDefinitionUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentDefinition definition = referenceResolver.requireAgent(name);
    ensureExpectedVersion(definition, name, rawExpected, expected);
    ModelRef modelRef = parseModelRef(updateDTO.getModel());
    referenceResolver.requireModelForUpdate(modelRef.providerName(), modelRef.modelName());
    definitionMutationFactory.update(definition, updateDTO);
    validateVariant(modelRef, definition.getVariant());
    AgentDefinitionConfigDTO config = configCodec.decode(definition.getConfigJson());
    validateConfig(config);
    AgentDefinition locked =
        referenceResolver.requireAgentAndSubagentsForUpdate(name, config.getSubagents());
    ensureExpectedVersion(locked, name, rawExpected, expected);
    try {
      if (!agentDefinitionRepository.updateByName(definition, expected)) {
        AgentDefinition reread = agentDefinitionRepository.getByName(name);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + name);
        }
        throw new AiVersionConflictException(
            RESOURCE, name, rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(
            MODEL_RESOURCE, MODEL_RESOURCE + " not found: " + modelRef, error);
      }
      throw error;
    }
    AgentDefinition reloaded = agentDefinitionRepository.getByName(name);
    return agentDefinitionConverter.convert(reloaded);
  }

  @Override
  @Transactional
  public void deleteAgent(String name, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentDefinition definition = referenceResolver.requireAgentForUpdate(name);
    ensureExpectedVersion(definition, name, expectedVersion, expected);
    referenceResolver.ensureNotReferencedAsSubagent(name);
    if (!agentDefinitionRepository.deleteByName(name, expected)) {
      AgentDefinition reread = agentDefinitionRepository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + name);
      }
      throw new AiVersionConflictException(
          RESOURCE, name, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
  }

  private static void ensureExpectedVersion(
      AgentDefinition definition, String name, String expectedVersion, long expected) {
    if (definition.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, name, expectedVersion, CatalogVersions.format(definition.getVersion()));
    }
  }

  private static ModelRef parseModelRef(String raw) {
    try {
      return ModelRef.parse(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "model must identify providerName/modelName", error);
    }
  }

  private void validateVariant(ModelRef modelRef, String variant) {
    try {
      variantResolver.resolve(modelRef.providerName(), modelRef.modelName(), variant);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  private void validateConfig(AgentDefinitionConfigDTO config) {
    try {
      configValidator.validate(config);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }
}
