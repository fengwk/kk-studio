package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.provider.AgentProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.persistence.PostgresqlIntegrityViolationClassifier;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ModelRef;

import java.util.Objects;

/**
 * 全局 model CRUD：字段与结构化配置规范化、Provider 父行锁、(providerName, name, version) CAS、改名改写 Agent 引用与删除引用检查。
 *
 * <p>结构化配置的 JSON 形状全部委托给 {@link AgentModelRuntimeConfigParser}。
 */
@Service
public class AgentModelService {

  private static final String RESOURCE = "agent_model";
  private static final String PROVIDER_RESOURCE = "agent_provider";
  private static final int NAME_MAX_LENGTH = 128;
  private static final int MODEL_ID_MAX_LENGTH = 256;

  private final AgentModelRepository repository;
  private final AgentProviderRepository providerRepository;
  private final AgentEditableSupport editableSupport;
  private final AgentModelRuntimeConfigParser runtimeConfigParser;

  public AgentModelService(
      AgentModelRepository repository,
      AgentProviderRepository providerRepository,
      AgentEditableSupport editableSupport,
      AgentModelRuntimeConfigParser runtimeConfigParser) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.runtimeConfigParser = Objects.requireNonNull(runtimeConfigParser, "runtimeConfigParser");
  }

  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return repository.page(pageQuery).map(this::toDto);
  }

  @Transactional
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    ModelRef ref =
        parseRef(
            createDTO == null ? null : createDTO.getProviderName(),
            createDTO == null ? null : createDTO.getName());
    if (providerRepository.getByNameForUpdate(ref.providerName()) == null) {
      throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
    }
    Mutation mutation = mutation(ref.modelName(), createDTO);
    AgentModel model =
        AgentModel.builder()
            .providerName(ref.providerName())
            .name(mutation.name())
            .modelId(mutation.modelId())
            .description(mutation.description())
            .configJson(mutation.configJson())
            .build();
    try {
      if (!repository.create(model)) {
        throw new IllegalStateException("create agent model failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists under this provider: " + ref, error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByProviderNameAndName(ref.providerName(), ref.modelName()));
  }

  @Transactional
  public AgentModelDTO updateModel(
      String providerName, String modelName, AgentModelUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    ModelRef ref = parseRef(providerName, modelName);
    AgentModel current = requireModelForUpdate(ref);
    ensureExpectedVersion(current, rawExpected, expected);
    Mutation mutation = mutation(updateDTO.getName(), updateDTO);
    AgentModel model =
        current.toBuilder()
            .name(mutation.name())
            .modelId(mutation.modelId())
            .description(mutation.description())
            .configJson(mutation.configJson())
            .build();
    try {
      if (!ref.modelName().equals(model.name())) {
        repository.rename(ref.modelName(), model, expected);
      } else if (!repository.updateByName(model, expected)) {
        AgentModel reread =
            repository.getByProviderNameAndName(ref.providerName(), ref.modelName());
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, rawExpected, CatalogVersions.format(reread.version()));
      }
    } catch (DuplicateKeyException error) {
      ModelRef targetRef = new ModelRef(model.providerName(), model.name());
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists under this provider: " + targetRef, error);
    }
    return toDto(repository.getByProviderNameAndName(ref.providerName(), model.name()));
  }

  @Transactional
  public void deleteModel(String providerName, String modelName, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    ModelRef ref = parseRef(providerName, modelName);
    AgentModel model = requireModelForUpdate(ref);
    ensureExpectedVersion(model, expectedVersion, expected);
    if (repository.hasAgents(ref.providerName(), ref.modelName())) {
      throw new AiInUseException(
          RESOURCE, RESOURCE + " in use by agents: " + ref.providerName() + "/" + ref.modelName());
    }
    if (!repository.deleteByName(ref.providerName(), ref.modelName(), expected)) {
      AgentModel reread = repository.getByProviderNameAndName(ref.providerName(), ref.modelName());
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.version()));
    }
  }

  /** 与写入共用字段和 config 校验，不写数据库。 */
  public void validateImport(String name, AgentModelEditablePropertiesDTO properties) {
    mutation(name, properties);
  }

  /** 将 model 行映射为公开 DTO；结构化配置由运行时配置解析器解码。 */
  public AgentModelDTO toDto(AgentModel model) {
    if (model.configJson() == null || model.configJson().isBlank()) {
      throw new AiValidationException(
          RESOURCE, "agent model config is required: " + model.providerName() + "/" + model.name());
    }
    AgentModelDTO dto = new AgentModelDTO();
    dto.setProviderName(model.providerName());
    dto.setName(model.name());
    dto.setModelId(model.modelId());
    dto.setDescription(model.description());
    dto.setConfig(runtimeConfigParser.decode(model.configJson()));
    dto.setVersion(CatalogVersions.format(model.version()));
    dto.setCreateTime(model.createTime());
    dto.setUpdateTime(model.updateTime());
    return dto;
  }

  private AgentModel requireModelForUpdate(ModelRef ref) {
    AgentModel model =
        repository.getByProviderNameAndNameForUpdate(ref.providerName(), ref.modelName());
    if (model == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return model;
  }

  private static void ensureExpectedVersion(
      AgentModel model, String expectedVersion, long expected) {
    if (model.version() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(model.version()));
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

  private Mutation mutation(String name, AgentModelEditablePropertiesDTO properties) {
    if (properties == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String normalizedName = name.strip();
    if (normalizedName.isEmpty()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(normalizedName)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    String modelId = editableSupport.trimToNull(properties.getModelId());
    if (modelId == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " modelId must not be blank");
    }
    if (!properties.getModelId().equals(modelId)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " modelId must not contain surrounding whitespace");
    }
    editableSupport.validateMaxLength(RESOURCE, "name", normalizedName, NAME_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "modelId", modelId, MODEL_ID_MAX_LENGTH);
    AgentModelConfigDTO config = properties.getConfig();
    if (config == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " config must not be null");
    }
    String configJson;
    try {
      configJson = runtimeConfigParser.encode(config);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(normalizedName, modelId, description, configJson);
  }

  private record Mutation(String name, String modelId, String description, String configJson) {}
}
