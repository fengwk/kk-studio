package fun.fengwk.kkstudio.platform.catalog.model;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.provider.Provider;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.persistence.PostgresqlIntegrityViolationClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 全局 model CRUD：单 MODEL 与 MODEL GROUP 共享全局名称空间。负责字段与结构化配置规范化、Provider 父行锁、组成员行锁与引用校验、(name,
 * version) CAS、改名改写 Agent 与组成员引用，以及删除与 Variant 移除的引用检查。
 *
 * <p>MODEL 配置的 JSON 形状委托给 {@link ModelRuntimeConfigParser}，组配置委托给 {@link
 * ModelGroupConfigCodec}；组的只读能力摘要由 {@link ModelGroupConfigDerivation} 从成员派生。
 */
@Service
public class ModelService {

  private static final String RESOURCE = "model";
  private static final String PROVIDER_RESOURCE = "provider";
  private static final int NAME_MAX_LENGTH = 128;
  private static final int MODEL_ID_MAX_LENGTH = 256;

  private final ModelRepository repository;
  private final ProviderRepository providerRepository;
  private final AgentEditableSupport editableSupport;
  private final ModelRuntimeConfigParser runtimeConfigParser;
  private final ModelGroupConfigCodec groupCodec;

  public ModelService(
      ModelRepository repository,
      ProviderRepository providerRepository,
      AgentEditableSupport editableSupport,
      ModelRuntimeConfigParser runtimeConfigParser,
      ModelGroupConfigCodec groupCodec) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.runtimeConfigParser = Objects.requireNonNull(runtimeConfigParser, "runtimeConfigParser");
    this.groupCodec = Objects.requireNonNull(groupCodec, "groupCodec");
  }

  public List<ModelDTO> listModels() {
    return repository.listAll().stream().map(this::toDto).toList();
  }

  @Transactional
  public ModelDTO createModel(ModelCreateDTO createDTO) {
    Mutation mutation = mutation(createDTO);
    validateMutation(mutation);
    Model model =
        Model.builder()
            .name(mutation.name())
            .kind(mutation.kind())
            .providerName(mutation.providerName())
            .modelId(mutation.modelId())
            .description(mutation.description())
            .configJson(mutation.configJson())
            .groupConfigJson(mutation.groupConfigJson())
            .build();
    try {
      if (!repository.create(model)) {
        throw new IllegalStateException("create model failed");
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          RESOURCE, RESOURCE + " name already exists: " + model.name(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw DomainException.notFound(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByName(model.name()));
  }

  @Transactional
  public ModelDTO updateModel(String modelName, ModelUpdateDTO updateDTO) {
    Long expected = expectedVersion(updateDTO);
    if (expected == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    String name = canonicalName(modelName);
    Model snapshot = repository.getByName(name);
    if (snapshot != null
        && snapshot.kind() == ModelKind.GROUP
        && updateDTO instanceof ModelGroupUpdateDTO groupUpdate) {
      // 先锁成员再锁组行：成员改名/删除走同一模型行锁，避免组配置引用已删除的成员或 Variant。
      lockMembers(snapshot, groupUpdate.getGroup());
    }
    Model current = requireModelForUpdate(name);
    ensureExpectedVersion(current, expected);
    Mutation mutation = mutation(updateDTO);
    if (current.kind() != mutation.kind()) {
      throw DomainException.validation(RESOURCE, "model type cannot be changed");
    }
    validateMutation(mutation);
    if (mutation.kind() == ModelKind.MODEL) {
      for (String removedVariant : removedVariants(current.configJson(), mutation.configJson())) {
        if (repository.hasVariantReferences(name, removedVariant)) {
          throw DomainException.inUse(
              RESOURCE,
              "model variant is still referenced: " + name + " variant=" + removedVariant);
        }
      }
    }
    Model model =
        current.toBuilder()
            .name(mutation.name())
            .providerName(mutation.providerName())
            .modelId(mutation.modelId())
            .description(mutation.description())
            .configJson(mutation.configJson())
            .groupConfigJson(mutation.groupConfigJson())
            .build();
    try {
      if (!name.equals(model.name())) {
        repository.rename(name, model, expected);
      } else if (!repository.updateByName(model, expected)) {
        Model reread = repository.getByName(name);
        if (reread == null) {
          throw DomainException.notFound(RESOURCE);
        }
        throw DomainException.versionConflict(RESOURCE, expected, reread.version());
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          RESOURCE, RESOURCE + " name already exists: " + model.name(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw DomainException.notFound(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByName(model.name()));
  }

  @Transactional
  public void deleteModel(String modelName, long expected) {
    String name = canonicalName(modelName);
    Model model = requireModelForUpdate(name);
    ensureExpectedVersion(model, expected);
    if (repository.hasAgents(name)) {
      throw DomainException.inUse(RESOURCE, RESOURCE + " in use by agents: " + name);
    }
    if (repository.hasGroupsReferencing(name)) {
      throw DomainException.inUse(RESOURCE, RESOURCE + " in use by model groups: " + name);
    }
    if (!repository.deleteByName(name, expected)) {
      Model reread = repository.getByName(name);
      if (reread == null) {
        throw DomainException.notFound(RESOURCE);
      }
      throw DomainException.versionConflict(RESOURCE, expected, reread.version());
    }
  }

  /** 与写入共用字段、结构化配置与组结构校验，不读写数据库。 */
  public void validateImport(String name, ModelCreateDTO properties) {
    canonicalName(name);
    mutation(properties);
  }

  /** 将 model 行映射为公开 DTO；GROUP 的 config 是从成员派生的只读能力摘要，不承载组级价格或 Variant。 */
  public ModelDTO toDto(Model model) {
    if (model.kind() == ModelKind.MODEL) {
      SingleModelDTO dto = new SingleModelDTO();
      dto.setName(model.name());
      dto.setDescription(model.description());
      dto.setProviderName(model.providerName());
      dto.setModelId(model.modelId());
      dto.setConfig(decodeConfig(model));
      dto.setVersion(model.version());
      dto.setCreatedAt(model.createdAt());
      dto.setUpdatedAt(model.updatedAt());
      return dto;
    }
    ModelGroupConfigDTO group = decodeGroup(model);
    ModelGroupDTO dto = new ModelGroupDTO();
    dto.setName(model.name());
    dto.setDescription(model.description());
    dto.setGroup(group);
    dto.setConfig(deriveGroupConfig(group));
    dto.setVersion(model.version());
    dto.setCreatedAt(model.createdAt());
    dto.setUpdatedAt(model.updatedAt());
    return dto;
  }

  private Model requireModelForUpdate(String name) {
    Model model = repository.getByNameForUpdate(name);
    if (model == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return model;
  }

  private Provider requireProviderForUpdate(String providerName) {
    Provider provider = providerRepository.getByNameForUpdate(providerName);
    if (provider == null) {
      throw DomainException.notFound(PROVIDER_RESOURCE);
    }
    return provider;
  }

  /** 校验每个 Variant 的提示缓存留存档位是否被所属 Provider 的类型支持。 模型保存与配置导入共用此校验，确保不支持时给出一致的 validation 错误。 */
  public static void validateVariantRetentions(
      String providerName, ProviderType providerType, ModelConfigDTO config) {
    Set<PromptCacheRetention> supported =
        Objects.requireNonNull(providerType, "providerType").promptCacheRetentions();
    for (ModelVariantDTO variant : config.getVariants()) {
      PromptCacheRetention retention = variant.getPromptCacheRetention();
      if (retention == null) {
        throw DomainException.validation(
            RESOURCE, "promptCacheRetention is required for variant: " + variant.getId());
      }
      if (!supported.contains(retention)) {
        throw DomainException.validation(
            RESOURCE,
            "provider "
                + providerName
                + " ("
                + providerType.wireValue()
                + ") does not support prompt cache retention "
                + retention
                + " for variant: "
                + variant.getId());
      }
    }
  }

  private void validateMutation(Mutation mutation) {
    if (mutation.kind() == ModelKind.MODEL) {
      Provider provider = requireProviderForUpdate(mutation.providerName());
      validateVariantRetentions(
          provider.name(), provider.providerType(), decodeConfig(mutation.configJson()));
    } else {
      validateMembers(groupCodec.decode(mutation.groupConfigJson()));
    }
  }

  /** 在锁定组自身行之前按 canonical 名称顺序锁定旧、新成员行。组更新与成员改名/删除共用同一把成员行锁，避免旧成员在更新期间被改名导致 JSON 失效，也避免新成员被并发删除。 */
  private void lockMembers(Model existing, ModelGroupConfigDTO group) {
    if (group == null) {
      return;
    }
    groupCodec.validateShape(group);
    Set<String> names = new TreeSet<>();
    if (existing.groupConfigJson() != null) {
      decodeStoredGroup(existing, existing.groupConfigJson())
          .getMembers()
          .forEach(member -> names.add(member.getModelName()));
    }
    group.getMembers().forEach(member -> names.add(member.getModelName()));
    names.forEach(repository::getByNameForUpdate);
  }

  /** 成员必须是已存在的单 MODEL，Variant 由该 Model 当前 config 声明，且成员之间存在共同输入模态。 */
  private void validateMembers(ModelGroupConfigDTO group) {
    group.getMembers().stream()
        .map(ModelGroupMemberDTO::getModelName)
        .distinct()
        .sorted()
        .forEach(repository::getByNameForUpdate);
    List<ModelConfigDTO> memberConfigs = new ArrayList<>();
    for (int index = 0; index < group.getMembers().size(); index++) {
      ModelGroupMemberDTO member = group.getMembers().get(index);
      String path = "group.members[" + index + "]";
      Model model = repository.getByNameForUpdate(member.getModelName());
      if (model == null) {
        throw DomainException.validation(
            RESOURCE, path + ".modelName references an unknown model: " + member.getModelName());
      }
      if (model.kind() != ModelKind.MODEL) {
        throw DomainException.validation(
            RESOURCE, path + ".modelName must reference a MODEL, not a GROUP");
      }
      ModelConfigDTO config;
      try {
        config = runtimeConfigParser.decode(model.configJson());
      } catch (IllegalArgumentException error) {
        throw DomainException.validation(
            RESOURCE, path + ".modelName has an invalid config: " + error.getMessage(), error);
      }
      boolean declared =
          config.getVariants().stream()
              .anyMatch(variant -> variant.getId().equals(member.getVariant()));
      if (!declared) {
        throw DomainException.validation(
            RESOURCE,
            path
                + ".variant is not declared by model "
                + member.getModelName()
                + ": "
                + member.getVariant());
      }
      memberConfigs.add(config);
    }
    try {
      ModelGroupConfigDerivation.derive(memberConfigs);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }
  }

  /** 更新后从 config 中删掉的 Variant；调用方据此拒绝仍被 Agent/组引用的删除。 */
  private List<String> removedVariants(String previousConfigJson, String nextConfigJson) {
    ModelConfigDTO before = runtimeConfigParser.decode(previousConfigJson);
    ModelConfigDTO after = runtimeConfigParser.decode(nextConfigJson);
    List<String> removed = new ArrayList<>();
    for (ModelVariantDTO variant : before.getVariants()) {
      boolean retained =
          after.getVariants().stream()
              .anyMatch(candidate -> candidate.getId().equals(variant.getId()));
      if (!retained) {
        removed.add(variant.getId());
      }
    }
    return removed;
  }

  private ModelConfigDTO decodeConfig(Model model) {
    if (model.configJson() == null || model.configJson().isBlank()) {
      throw DomainException.validation(RESOURCE, "model config is required: " + model.name());
    }
    return runtimeConfigParser.decode(model.configJson());
  }

  private ModelConfigDTO decodeConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      throw DomainException.validation(RESOURCE, "model config is required");
    }
    return runtimeConfigParser.decode(configJson);
  }

  private ModelGroupConfigDTO decodeGroup(Model model) {
    if (model.groupConfigJson() == null || model.groupConfigJson().isBlank()) {
      throw DomainException.validation(RESOURCE, "model group config is required: " + model.name());
    }
    return decodeStoredGroup(model, model.groupConfigJson());
  }

  /** 持久化的组配置必须由写入路径保证合法：损坏时按内部错误处理，而不是请求错误。 */
  private ModelGroupConfigDTO decodeStoredGroup(Model model, String groupConfigJson) {
    try {
      return groupCodec.decode(groupConfigJson);
    } catch (DomainException error) {
      throw new IllegalStateException(
          "stored model group config is invalid: " + model.name(), error);
    }
  }

  private ModelConfigDTO deriveGroupConfig(ModelGroupConfigDTO group) {
    List<ModelConfigDTO> members = new ArrayList<>(group.getMembers().size());
    for (ModelGroupMemberDTO member : group.getMembers()) {
      Model memberModel = repository.getByName(member.getModelName());
      if (memberModel == null || memberModel.kind() != ModelKind.MODEL) {
        throw DomainException.validation(
            RESOURCE, "group member model is missing: " + member.getModelName());
      }
      members.add(decodeConfig(memberModel));
    }
    try {
      return ModelGroupConfigDerivation.derive(members);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }
  }

  private static void ensureExpectedVersion(Model model, long expected) {
    if (model.version() != expected) {
      throw DomainException.versionConflict(RESOURCE, expected, model.version());
    }
  }

  private static Long expectedVersion(ModelUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    return updateDTO.getExpectedVersion();
  }

  private Mutation mutation(ModelCreateDTO properties) {
    if (properties == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    return switch (properties) {
      case SingleModelCreateDTO single -> modelMutation(
          single.getName(),
          single.getDescription(),
          single.getProviderName(),
          single.getModelId(),
          single.getConfig());
      case ModelGroupCreateDTO group -> groupMutation(
          group.getName(), group.getDescription(), group.getGroup());
    };
  }

  private Mutation mutation(ModelUpdateDTO properties) {
    if (properties == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    return switch (properties) {
      case SingleModelUpdateDTO single -> modelMutation(
          single.getName(),
          single.getDescription(),
          single.getProviderName(),
          single.getModelId(),
          single.getConfig());
      case ModelGroupUpdateDTO group -> groupMutation(
          group.getName(), group.getDescription(), group.getGroup());
    };
  }

  private Mutation modelMutation(
      String rawName,
      String rawDescription,
      String providerName,
      String rawModelId,
      ModelConfigDTO config) {
    String name = canonicalName(rawName);
    editableSupport.validateMaxLength(RESOURCE, "name", name, NAME_MAX_LENGTH);
    if (providerName == null || providerName.isBlank()) {
      throw DomainException.validation(RESOURCE, "providerName must not be blank");
    }
    if (!providerName.equals(providerName.strip())) {
      throw DomainException.validation(
          RESOURCE, "providerName must not contain surrounding whitespace");
    }
    String modelId = editableSupport.trimToNull(rawModelId);
    if (modelId == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " modelId must not be blank");
    }
    if (!rawModelId.equals(modelId)) {
      throw DomainException.validation(
          RESOURCE, RESOURCE + " modelId must not contain surrounding whitespace");
    }
    editableSupport.validateMaxLength(RESOURCE, "modelId", modelId, MODEL_ID_MAX_LENGTH);
    if (config == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " config must not be null");
    }
    String configJson;
    try {
      configJson = runtimeConfigParser.encode(config);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(
        name,
        ModelKind.MODEL,
        providerName,
        modelId,
        editableSupport.trimToNull(rawDescription),
        configJson,
        null);
  }

  private Mutation groupMutation(String rawName, String rawDescription, ModelGroupConfigDTO group) {
    String name = canonicalName(rawName);
    editableSupport.validateMaxLength(RESOURCE, "name", name, NAME_MAX_LENGTH);
    if (group == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " group must not be null");
    }
    String groupConfigJson = groupCodec.encode(group);
    return new Mutation(
        name,
        ModelKind.GROUP,
        null,
        null,
        editableSupport.trimToNull(rawDescription),
        null,
        groupConfigJson);
  }

  private static String canonicalName(String name) {
    if (name == null || name.isBlank()) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(name.strip())) {
      throw DomainException.validation(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    return name;
  }

  private record Mutation(
      String name,
      ModelKind kind,
      String providerName,
      String modelId,
      String description,
      String configJson,
      String groupConfigJson) {}
}
