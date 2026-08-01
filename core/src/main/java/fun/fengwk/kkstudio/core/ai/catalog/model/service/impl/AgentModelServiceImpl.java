package fun.fengwk.kkstudio.core.ai.catalog.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.ai.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.core.persistence.PostgresqlIntegrityViolationClassifier;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelRef;

/** Global model CRUD. */
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
    ModelRef ref =
        parseRef(
            createDTO == null ? null : createDTO.getProviderName(),
            createDTO == null ? null : createDTO.getName());
    referenceResolver.requireProvider(ref.providerName());
    AgentModel model =
        modelMutationFactory.newModel(ref.providerName(), ref.modelName(), createDTO);
    referenceResolver.ensureNameAvailable(ref.providerName(), ref.modelName());
    try {
      if (!agentModelRepository.create(model)) {
        throw new IllegalStateException("create agent model failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists under this provider: " + ref, error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(
            PROVIDER_RESOURCE, PROVIDER_RESOURCE + " not found: " + ref.providerName(), error);
      }
      throw error;
    }
    AgentModel loaded =
        agentModelRepository.getByProviderNameAndName(ref.providerName(), ref.modelName());
    return agentModelConverter.convert(loaded);
  }

  @Override
  @Transactional
  public AgentModelDTO updateModel(
      String providerName, String modelName, AgentModelUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    ModelRef ref = parseRef(providerName, modelName);
    AgentModel model = referenceResolver.requireModel(ref.providerName(), ref.modelName());
    ensureExpectedVersion(model, ref, rawExpected, expected);
    modelMutationFactory.update(model, updateDTO);
    try {
      if (!agentModelRepository.updateByName(model, expected)) {
        AgentModel reread =
            agentModelRepository.getByProviderNameAndName(ref.providerName(), ref.modelName());
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + ref);
        }
        throw new AiVersionConflictException(
            RESOURCE, ref.toString(), rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists under this provider: " + ref, error);
    }
    AgentModel reloaded =
        agentModelRepository.getByProviderNameAndName(ref.providerName(), ref.modelName());
    return agentModelConverter.convert(reloaded);
  }

  @Override
  @Transactional
  public void deleteModel(String providerName, String modelName, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    ModelRef ref = parseRef(providerName, modelName);
    AgentModel model = referenceResolver.requireModel(ref.providerName(), ref.modelName());
    ensureExpectedVersion(model, ref, expectedVersion, expected);
    referenceResolver.ensureDeletable(ref.providerName(), ref.modelName());
    try {
      if (!agentModelRepository.deleteByName(ref.providerName(), ref.modelName(), expected)) {
        AgentModel reread =
            agentModelRepository.getByProviderNameAndName(ref.providerName(), ref.modelName());
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + ref);
        }
        throw new AiVersionConflictException(
            RESOURCE, ref.toString(), expectedVersion, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiInUseException(RESOURCE, RESOURCE + " in use by agents: " + ref, error);
      }
      throw error;
    }
  }

  private static void ensureExpectedVersion(
      AgentModel model, ModelRef ref, String expectedVersion, long expected) {
    if (model.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, ref.toString(), expectedVersion, CatalogVersions.format(model.getVersion()));
    }
  }

  private static ModelRef parseRef(String providerName, String modelName) {
    try {
      return new ModelRef(providerName, modelName);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "model must identify providerName/modelName", error);
    }
  }
}
