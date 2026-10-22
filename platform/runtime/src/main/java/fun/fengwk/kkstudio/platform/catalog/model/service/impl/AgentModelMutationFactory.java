package fun.fengwk.kkstudio.platform.catalog.model.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.model.repo.AgentModelRepository;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelGroupConfigDerivation;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelRuntimeConfigParser;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 通过共享的类型化 codec 规范化并校验 MODEL / GROUP 两种模型资源。 */
@Component
public final class AgentModelMutationFactory {

  private static final String RESOURCE = "agent_model";
  private static final int NAME_MAX_LENGTH = 128;
  private static final int MODEL_ID_MAX_LENGTH = 256;

  private final AgentEditableSupport editableSupport;
  private final AgentModelRuntimeConfigParser runtimeConfigParser;
  private final AgentModelGroupConfigCodec groupCodec;
  private final AgentModelRepository modelRepository;

  public AgentModelMutationFactory(
      AgentEditableSupport editableSupport,
      AgentModelRuntimeConfigParser runtimeConfigParser,
      AgentModelGroupConfigCodec groupCodec,
      AgentModelRepository modelRepository) {
    this.editableSupport = editableSupport;
    this.runtimeConfigParser = runtimeConfigParser;
    this.groupCodec = groupCodec;
    this.modelRepository = Objects.requireNonNull(modelRepository, "modelRepository");
  }

  /** 与写入共用字段和 config 校验，不写数据库。 */
  public void validateImport(String name, AgentModelEditablePropertiesDTO properties) {
    newMutation(name, properties, null);
  }

  AgentModel newModel(String name, AgentModelEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(name, properties, null);
    validateMutationMembers(mutation);
    AgentModel model = new AgentModel();
    apply(model, mutation);
    return model;
  }

  void update(AgentModel model, AgentModelEditablePropertiesDTO properties) {
    Mutation mutation =
        newMutation(properties == null ? null : properties.getName(), properties, model);
    validateMutationMembers(mutation);
    apply(model, mutation);
  }

  /**
   * 在锁定组自身行之前按 canonical 名称顺序锁定旧、新成员行。
   *
   * <p>组更新与成员改名/删除共用同一把成员行锁，避免旧成员在更新期间被改名导致 JSON 失效，也避免新成员被并发删除。
   */
  void lockMembers(AgentModel existing, AgentModelEditablePropertiesDTO properties) {
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
    if (existing != null && existing.getGroupConfigJson() != null) {
      groupCodec
          .decode(existing.getGroupConfigJson())
          .getMembers()
          .forEach(member -> names.add(member.getModelName()));
    }
    properties.getGroup().getMembers().forEach(member -> names.add(member.getModelName()));
    names.forEach(modelRepository::getByNameForUpdate);
  }

  /** 更新后从 config 中删掉的 Variant；调用方据此拒绝仍被 Agent/组引用的删除。 */
  List<String> removedVariants(String previousConfigJson, AgentModel updated) {
    if (updated.getKind() != AgentModelKind.MODEL) {
      return List.of();
    }
    AgentModelConfigDTO before = runtimeConfigParser.decode(previousConfigJson);
    AgentModelConfigDTO after = runtimeConfigParser.decode(updated.getConfigJson());
    List<String> removed = new ArrayList<>();
    for (var variant : before.getVariants()) {
      boolean retained =
          after.getVariants().stream()
              .anyMatch(candidate -> candidate.getId().equals(variant.getId()));
      if (!retained) {
        removed.add(variant.getId());
      }
    }
    return List.copyOf(removed);
  }

  private void apply(AgentModel model, Mutation mutation) {
    model.setName(mutation.name());
    model.setKind(mutation.kind());
    model.setProviderName(mutation.providerName());
    model.setModelId(mutation.modelId());
    model.setDescription(mutation.description());
    model.setConfigJson(mutation.configJson());
    model.setGroupConfigJson(mutation.groupConfigJson());
  }

  private Mutation newMutation(
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
    if (existing != null && existing.getKind() != kind) {
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
    String providerName = canonicalProvider(properties.getProviderName());
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
    try {
      groupCodec.validateShape(group);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(
        name, AgentModelKind.GROUP, null, null, description, null, groupCodec.encode(group));
  }

  private void validateMutationMembers(Mutation mutation) {
    if (mutation.kind() == AgentModelKind.GROUP) {
      validateMembers(groupCodec.decode(mutation.groupConfigJson()));
    }
  }

  /** 成员必须是已存在的单 MODEL，且 Variant 由该 Model 当前 config 声明。 */
  private void validateMembers(AgentModelGroupDTO group) {
    lockMemberRows(group);
    List<AgentModelConfigDTO> memberConfigs = new ArrayList<>();
    for (int index = 0; index < group.getMembers().size(); index++) {
      AgentModelGroupMemberDTO member = group.getMembers().get(index);
      String path = "group.members[" + index + "]";
      AgentModel model = modelRepository.getByNameForUpdate(member.getModelName());
      if (model == null) {
        throw new AiValidationException(
            RESOURCE, path + ".modelName references an unknown model: " + member.getModelName());
      }
      if (model.getKind() != AgentModelKind.MODEL) {
        throw new AiValidationException(
            RESOURCE, path + ".modelName must reference a MODEL, not a GROUP");
      }
      AgentModelConfigDTO config;
      try {
        config = runtimeConfigParser.decode(model.getConfigJson());
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

  private void lockMemberRows(AgentModelGroupDTO group) {
    group.getMembers().stream()
        .map(AgentModelGroupMemberDTO::getModelName)
        .distinct()
        .sorted()
        .forEach(modelRepository::getByNameForUpdate);
  }

  private static String canonicalName(String name) {
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String normalized = name.strip();
    if (normalized.isEmpty()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(normalized)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    return normalized;
  }

  private static String canonicalProvider(String providerName) {
    if (providerName == null || providerName.isBlank()) {
      throw new AiValidationException(RESOURCE, "providerName must not be blank");
    }
    if (!providerName.equals(providerName.strip())) {
      throw new AiValidationException(
          RESOURCE, "providerName must not contain surrounding whitespace");
    }
    return providerName;
  }

  record Mutation(
      String name,
      AgentModelKind kind,
      String providerName,
      String modelId,
      String description,
      String configJson,
      String groupConfigJson) {}
}
