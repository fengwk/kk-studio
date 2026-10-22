package fun.fengwk.kkstudio.platform.catalog.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.platform.catalog.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.persistence.PostgresqlIntegrityViolationClassifier;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;

/** 全局 model CRUD：name 跨 MODEL/GROUP 原子唯一。 */
@AllArgsConstructor
@Service
public class AgentModelServiceImpl implements AgentModelService {

  private static final String RESOURCE = "agent_model";
  private static final String PROVIDER_RESOURCE = "agent_provider";

  private final AgentModelRepository agentModelRepository;
  private final AgentModelConverter agentModelConverter;
  private final AgentModelMutationFactory modelMutationFactory;
  private final AgentModelReferenceResolver referenceResolver;

  @Override
  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return agentModelRepository.page(pageQuery).map(agentModelConverter::convert);
  }

  @Override
  @Transactional
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    String name = createDTO == null ? null : createDTO.getName();
    AgentModel model = modelMutationFactory.newModel(name, createDTO);
    if (model.getKind() == AgentModelKind.MODEL) {
      referenceResolver.requireProviderForUpdate(model.getProviderName());
    }
    try {
      if (!agentModelRepository.create(model)) {
        throw new IllegalStateException("create agent model failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists: " + model.getName(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return agentModelConverter.convert(agentModelRepository.getByName(model.getName()));
  }

  @Override
  @Transactional
  public AgentModelDTO updateModel(String modelName, AgentModelUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentModel model = referenceResolver.requireModelForUpdate(canonicalName(modelName));
    ensureExpectedVersion(model, rawExpected, expected);
    String previousConfigJson = model.getConfigJson();
    modelMutationFactory.update(model, updateDTO);
    if (model.getKind() == AgentModelKind.MODEL) {
      referenceResolver.requireProviderForUpdate(model.getProviderName());
      for (String removedVariant :
          modelMutationFactory.removedVariants(previousConfigJson, model)) {
        if (agentModelRepository.hasVariantReferences(modelName, removedVariant)) {
          throw new AiInUseException(
              RESOURCE,
              "model variant is still referenced: " + modelName + " variant=" + removedVariant);
        }
      }
    }
    boolean isRename = !modelName.equals(model.getName());
    try {
      if (isRename) {
        agentModelRepository.rename(modelName, model, expected);
      } else if (!agentModelRepository.updateByName(model, expected)) {
        AgentModel reread = agentModelRepository.getByName(modelName);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists: " + model.getName(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return agentModelConverter.convert(agentModelRepository.getByName(model.getName()));
  }

  @Override
  @Transactional
  public void deleteModel(String modelName, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    String name = canonicalName(modelName);
    AgentModel model = referenceResolver.requireModelForUpdate(name);
    ensureExpectedVersion(model, expectedVersion, expected);
    referenceResolver.ensureDeletable(name);
    if (!agentModelRepository.deleteByName(name, expected)) {
      AgentModel reread = agentModelRepository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
  }

  private static void ensureExpectedVersion(
      AgentModel model, String expectedVersion, long expected) {
    if (model.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(model.getVersion()));
    }
  }

  private static String canonicalName(String modelName) {
    if (modelName == null || modelName.isBlank()) {
      throw new AiValidationException(RESOURCE, "modelName must not be blank");
    }
    if (!modelName.equals(modelName.strip())) {
      throw new AiValidationException(
          RESOURCE, "modelName must not contain surrounding whitespace");
    }
    return modelName;
  }
}
