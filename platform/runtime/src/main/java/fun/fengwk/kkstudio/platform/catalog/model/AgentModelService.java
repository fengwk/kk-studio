package fun.fengwk.kkstudio.platform.catalog.model;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigDerivation;
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
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 全局 model CRUD：单 MODEL 与 MODEL GROUP 共享全局名称空间。负责字段与结构化配置规范化、Provider 父行锁、组成员行锁与引用校验、(name,
 * version) CAS、改名改写 Agent 与组成员引用，以及删除与 Variant 移除的引用检查。
 *
 * <p>MODEL 配置的 JSON 形状委托给 {@link AgentModelRuntimeConfigParser}，组配置委托给 {@link
 * AgentModelGroupConfigCodec}；组的只读能力摘要由 {@link AgentModelGroupConfigDerivation} 从成员派生。
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
  private final AgentModelGroupConfigCodec groupCodec;

  public AgentModelService(
      AgentModelRepository repository,
      AgentProviderRepository providerRepository,
      AgentEditableSupport editableSupport,
      AgentModelRuntimeConfigParser runtimeConfigParser,
      AgentModelGroupConfigCodec groupCodec) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.providerRepository = Objects.requireNonNull(providerRepository, "providerRepository");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.runtimeConfigParser = Objects.requireNonNull(runtimeConfigParser, "runtimeConfigParser");
    this.groupCodec = Objects.requireNonNull(groupCodec, "groupCodec");
  }

  public Page<AgentModelDTO> pageModels(PageQuery pageQuery) {
    return repository.page(pageQuery).map(this::toDto);
  }

  @Transactional
  public AgentModelDTO createModel(AgentModelCreateDTO createDTO) {
    Mutation mutation = mutation(createDTO == null ? null : createDTO.getName(), createDTO, null);
    if (mutation.kind() == AgentModelKind.MODEL) {
      requireProviderForUpdate(mutation.providerName());
    } else {
      validateMembers(groupCodec.decode(mutation.groupConfigJson()));
    }
    AgentModel model =
        AgentModel.builder()
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
        throw new IllegalStateException("create agent model failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists: " + model.name(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByName(model.name()));
  }

  @Transactional
  public AgentModelDTO updateModel(String modelName, AgentModelUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    String name = canonicalName(modelName);
    AgentModel snapshot = repository.getByName(name);
    if (snapshot != null && snapshot.kind() == AgentModelKind.GROUP) {
      // 先锁成员再锁组行：成员改名/删除走同一模型行锁，避免组配置引用已删除的成员或 Variant。
      lockMembers(snapshot, updateDTO);
    }
    AgentModel current = requireModelForUpdate(name);
    ensureExpectedVersion(current, rawExpected, expected);
    Mutation mutation = mutation(updateDTO.getName(), updateDTO, current);
    if (mutation.kind() == AgentModelKind.MODEL) {
      requireProviderForUpdate(mutation.providerName());
      for (String removedVariant : removedVariants(current.configJson(), mutation.configJson())) {
        if (repository.hasVariantReferences(name, removedVariant)) {
          throw new AiInUseException(
              RESOURCE,
              "model variant is still referenced: " + name + " variant=" + removedVariant);
        }
      }
    } else {
      validateMembers(groupCodec.decode(mutation.groupConfigJson()));
    }
    AgentModel model =
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
        AgentModel reread = repository.getByName(name);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, rawExpected, CatalogVersions.format(reread.version()));
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, RESOURCE + " name already exists: " + model.name(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByName(model.name()));
  }

  @Transactional
  public void deleteModel(String modelName, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    String name = canonicalName(modelName);
    AgentModel model = requireModelForUpdate(name);
    ensureExpectedVersion(model, expectedVersion, expected);
    if (repository.hasAgents(name)) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by agents: " + name);
    }
    if (repository.hasGroupsReferencing(name)) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by model groups: " + name);
    }
    if (!repository.deleteByName(name, expected)) {
      AgentModel reread = repository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.version()));
    }
  }

  /** 与写入共用字段、结构化配置与组结构校验，不读写数据库。 */
  public void validateImport(String name, AgentModelEditablePropertiesDTO properties) {
    mutation(name, properties, null);
  }

  /** 将 model 行映射为公开 DTO；GROUP 的 config 是从成员派生的只读能力摘要，不承载组级价格或 Variant。 */
  public AgentModelDTO toDto(AgentModel model) {
    AgentModelDTO dto = new AgentModelDTO();
    dto.setName(model.name());
    dto.setKind(model.kind());
    dto.setDescription(model.description());
    if (model.kind() == AgentModelKind.MODEL) {
      dto.setProviderName(model.providerName());
      dto.setModelId(model.modelId());
      dto.setConfig(decodeConfig(model));
    } else {
      AgentModelGroupDTO group = decodeGroup(model);
      dto.setGroup(group);
      dto.setConfig(deriveGroupConfig(group));
    }
    dto.setVersion(CatalogVersions.format(model.version()));
    dto.setCreateTime(model.createTime());
    dto.setUpdateTime(model.updateTime());
    return dto;
  }

  private AgentModel requireModelForUpdate(String name) {
    AgentModel model = repository.getByNameForUpdate(name);
    if (model == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return model;
  }

  private void requireProviderForUpdate(String providerName) {
    if (providerRepository.getByNameForUpdate(providerName) == null) {
      throw new AiResourceNotFoundException(PROVIDER_RESOURCE);
    }
  }

  /** 在锁定组自身行之前按 canonical 名称顺序锁定旧、新成员行。组更新与成员改名/删除共用同一把成员行锁，避免旧成员在更新期间被改名导致 JSON 失效，也避免新成员被并发删除。 */
  private void lockMembers(AgentModel existing, AgentModelEditablePropertiesDTO properties) {
    if (properties == null
        || properties.getKind() != AgentModelKind.GROUP
        || properties.getGroup() == null) {
      return;
    }
    try {
      groupCodec.validateShape(properties.getGroup());
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    Set<String> names = new TreeSet<>();
    if (existing.groupConfigJson() != null) {
      groupCodec
          .decode(existing.groupConfigJson())
          .getMembers()
          .forEach(member -> names.add(member.getModelName()));
    }
    properties.getGroup().getMembers().forEach(member -> names.add(member.getModelName()));
    names.forEach(repository::getByNameForUpdate);
  }

  /** 成员必须是已存在的单 MODEL，Variant 由该 Model 当前 config 声明，且成员之间存在共同输入模态。 */
  private void validateMembers(AgentModelGroupDTO group) {
    group.getMembers().stream()
        .map(AgentModelGroupMemberDTO::getModelName)
        .distinct()
        .sorted()
        .forEach(repository::getByNameForUpdate);
    List<AgentModelConfigDTO> memberConfigs = new ArrayList<>();
    for (int index = 0; index < group.getMembers().size(); index++) {
      AgentModelGroupMemberDTO member = group.getMembers().get(index);
      String path = "group.members[" + index + "]";
      AgentModel model = repository.getByNameForUpdate(member.getModelName());
      if (model == null) {
        throw new AiValidationException(
            RESOURCE, path + ".modelName references an unknown model: " + member.getModelName());
      }
      if (model.kind() != AgentModelKind.MODEL) {
        throw new AiValidationException(
            RESOURCE, path + ".modelName must reference a MODEL, not a GROUP");
      }
      AgentModelConfigDTO config;
      try {
        config = runtimeConfigParser.decode(model.configJson());
      } catch (IllegalArgumentException error) {
        throw new AiValidationException(
            RESOURCE, path + ".modelName has an invalid config: " + error.getMessage(), error);
      }
      boolean declared =
          config.getVariants().stream()
              .anyMatch(variant -> variant.getId().equals(member.getVariant()));
      if (!declared) {
        throw new AiValidationException(
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
      AgentModelGroupConfigDerivation.derive(memberConfigs);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  /** 更新后从 config 中删掉的 Variant；调用方据此拒绝仍被 Agent/组引用的删除。 */
  private List<String> removedVariants(String previousConfigJson, String nextConfigJson) {
    AgentModelConfigDTO before = runtimeConfigParser.decode(previousConfigJson);
    AgentModelConfigDTO after = runtimeConfigParser.decode(nextConfigJson);
    List<String> removed = new ArrayList<>();
    for (AgentModelVariantDTO variant : before.getVariants()) {
      boolean retained =
          after.getVariants().stream()
              .anyMatch(candidate -> candidate.getId().equals(variant.getId()));
      if (!retained) {
        removed.add(variant.getId());
      }
    }
    return removed;
  }

  private AgentModelConfigDTO decodeConfig(AgentModel model) {
    if (model.configJson() == null || model.configJson().isBlank()) {
      throw new AiValidationException(RESOURCE, "agent model config is required: " + model.name());
    }
    return runtimeConfigParser.decode(model.configJson());
  }

  private AgentModelGroupDTO decodeGroup(AgentModel model) {
    if (model.groupConfigJson() == null || model.groupConfigJson().isBlank()) {
      throw new AiValidationException(RESOURCE, "model group config is required: " + model.name());
    }
    return groupCodec.decode(model.groupConfigJson());
  }

  private AgentModelConfigDTO deriveGroupConfig(AgentModelGroupDTO group) {
    List<AgentModelConfigDTO> members = new ArrayList<>(group.getMembers().size());
    for (AgentModelGroupMemberDTO member : group.getMembers()) {
      AgentModel memberModel = repository.getByName(member.getModelName());
      if (memberModel == null || memberModel.kind() != AgentModelKind.MODEL) {
        throw new AiValidationException(
            RESOURCE, "group member model is missing: " + member.getModelName());
      }
      members.add(decodeConfig(memberModel));
    }
    try {
      return AgentModelGroupConfigDerivation.derive(members);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }

  private static void ensureExpectedVersion(
      AgentModel model, String expectedVersion, long expected) {
    if (model.version() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(model.version()));
    }
  }

  private Mutation mutation(
      String name, AgentModelEditablePropertiesDTO properties, AgentModel existing) {
    if (properties == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String normalizedName = canonicalName(name);
    editableSupport.validateMaxLength(RESOURCE, "name", normalizedName, NAME_MAX_LENGTH);
    AgentModelKind kind = properties.getKind();
    if (kind == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " kind is required");
    }
    if (existing != null && existing.kind() != kind) {
      throw new AiValidationException(RESOURCE, "model kind cannot be changed");
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    return switch (kind) {
      case MODEL -> modelMutation(normalizedName, description, properties);
      case GROUP -> groupMutation(normalizedName, description, properties);
    };
  }

  private Mutation modelMutation(
      String name, String description, AgentModelEditablePropertiesDTO properties) {
    if (properties.getGroup() != null) {
      throw new AiValidationException(RESOURCE, "MODEL must not carry group config");
    }
    String providerName = properties.getProviderName();
    if (providerName == null || providerName.isBlank()) {
      throw new AiValidationException(RESOURCE, "providerName must not be blank");
    }
    if (!providerName.equals(providerName.strip())) {
      throw new AiValidationException(
          RESOURCE, "providerName must not contain surrounding whitespace");
    }
    String modelId = editableSupport.trimToNull(properties.getModelId());
    if (modelId == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " modelId must not be blank");
    }
    if (!properties.getModelId().equals(modelId)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " modelId must not contain surrounding whitespace");
    }
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
    return new Mutation(
        name, AgentModelKind.MODEL, providerName, modelId, description, configJson, null);
  }

  private Mutation groupMutation(
      String name, String description, AgentModelEditablePropertiesDTO properties) {
    if (properties.getProviderName() != null
        || properties.getModelId() != null
        || properties.getConfig() != null) {
      throw new AiValidationException(
          RESOURCE, "GROUP must not carry providerName, modelId or model config");
    }
    AgentModelGroupDTO group = properties.getGroup();
    if (group == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " group must not be null");
    }
    String groupConfigJson;
    try {
      groupConfigJson = groupCodec.encode(group);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(name, AgentModelKind.GROUP, null, null, description, null, groupConfigJson);
  }

  private static String canonicalName(String name) {
    if (name == null || name.isBlank()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(name.strip())) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    return name;
  }

  private record Mutation(
      String name,
      AgentModelKind kind,
      String providerName,
      String modelId,
      String description,
      String configJson,
      String groupConfigJson) {}
}
