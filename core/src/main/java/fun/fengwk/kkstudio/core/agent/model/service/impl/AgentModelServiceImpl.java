package fun.fengwk.kkstudio.core.agent.model.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.agent.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.core.agent.model.service.AgentModelService;
import fun.fengwk.kkstudio.core.agent.model.service.converter.AgentModelConverter;
import fun.fengwk.kkstudio.core.agent.model.service.model.AgentModel;
import fun.fengwk.kkstudio.core.ai.error.AiDuplicateException;
import fun.fengwk.kkstudio.core.ai.error.AiInUseException;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.ai.error.AiVersionConflictException;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.core.persistence.PostgresqlIntegrityViolationClassifier;
import fun.fengwk.kkstudio.share.model.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.model.AgentModelDTO;
import fun.fengwk.kkstudio.share.model.AgentModelUpdateDTO;

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
    long providerId = parseId(createDTO == null ? null : createDTO.getProviderId(), "providerId");
    referenceResolver.requireProvider(providerId);
    AgentModel model = modelMutationFactory.newModel(providerId, createDTO);
    referenceResolver.ensureNameAvailable(providerId, model.getName());
    try {
      if (!agentModelRepository.create(model)) {
        throw new IllegalStateException("create agent model failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE,
          RESOURCE + " name already exists under this provider: " + model.getName(),
          error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(
            PROVIDER_RESOURCE, PROVIDER_RESOURCE + " not found: " + providerId, error);
      }
      throw error;
    }
    AgentModel loaded = agentModelRepository.getById(model.getId());
    return agentModelConverter.convert(loaded);
  }

  @Override
  @Transactional
  public AgentModelDTO updateModel(long id, AgentModelUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentModel model = referenceResolver.requireModel(id);
    ensureExpectedVersion(model, id, rawExpected, expected);
    String currentName = model.getName();
    modelMutationFactory.update(model, updateDTO);
    // provider_id is immutable on update; uniqueness is still scoped to the owning provider.
    referenceResolver.ensureNameAvailable(model.getProviderId(), currentName, model.getName());
    try {
      if (!agentModelRepository.updateById(model, expected)) {
        AgentModel reread = agentModelRepository.getById(id);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
        }
        throw new AiVersionConflictException(
            RESOURCE, Long.toString(id), rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE,
          RESOURCE + " name already exists under this provider: " + model.getName(),
          error);
    }
    AgentModel reloaded = agentModelRepository.getById(id);
    return agentModelConverter.convert(reloaded);
  }

  @Override
  @Transactional
  public void deleteModel(long id, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentModel model = referenceResolver.requireModel(id);
    ensureExpectedVersion(model, id, expectedVersion, expected);
    referenceResolver.ensureDeletable(id);
    try {
      if (!agentModelRepository.deleteById(id, expected)) {
        AgentModel reread = agentModelRepository.getById(id);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
        }
        throw new AiVersionConflictException(
            RESOURCE,
            Long.toString(id),
            expectedVersion,
            CatalogVersions.format(reread.getVersion()));
      }
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiInUseException(RESOURCE, RESOURCE + " in use by agents: " + id, error);
      }
      throw error;
    }
  }

  private static void ensureExpectedVersion(
      AgentModel model, long id, String expectedVersion, long expected) {
    if (model.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, Long.toString(id), expectedVersion, CatalogVersions.format(model.getVersion()));
    }
  }

  private long parseId(String value, String field) {
    if (value == null) {
      throw new AiValidationException(RESOURCE, field + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new AiValidationException(RESOURCE, field + " must not be blank");
    }
    if (!trimmed.matches("^[1-9][0-9]*$")) {
      throw new AiValidationException(
          RESOURCE, field + " must be an unsigned positive decimal: " + value);
    }
    try {
      return Long.parseLong(trimmed);
    } catch (NumberFormatException error) {
      throw new AiValidationException(RESOURCE, field + " exceeds long range: " + value, error);
    }
  }
}
