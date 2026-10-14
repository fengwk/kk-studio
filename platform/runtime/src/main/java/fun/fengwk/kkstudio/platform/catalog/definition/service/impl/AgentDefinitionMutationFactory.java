package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.ModelRef;

/**
 * 规范化可编辑的 Agent 字段，并序列化严格的结构化执行配置。
 *
 * @author fengwk
 */
@Component
public final class AgentDefinitionMutationFactory {

  private static final String RESOURCE = "agent_definition";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int VARIANT_MAX_LENGTH = 64;

  private final AgentEditableSupport editableSupport;
  private final AgentDefinitionConfigCodec configCodec;

  public AgentDefinitionMutationFactory(
      AgentEditableSupport editableSupport, AgentDefinitionConfigCodec configCodec) {
    this.editableSupport = editableSupport;
    this.configCodec = configCodec;
  }

  /** 与写入共用字段和 config 校验，不写数据库；导入的 Agent 一律是用户类型，模型必填。 */
  public void validateImport(String name, AgentDefinitionEditablePropertiesDTO properties) {
    newMutation(name, properties, true);
  }

  AgentDefinition newUserAgent(String name, AgentDefinitionEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(name, properties, true);
    AgentDefinition definition = new AgentDefinition();
    definition.setType(AgentDefinitionType.USER);
    apply(definition, mutation);
    return definition;
  }

  /** 内置 Agent 允许显式未配置模型：model 为 null/空白表示未配置，variant 也必须为 null。 */
  AgentDefinition newBuiltinAgent(String name, AgentDefinitionEditablePropertiesDTO properties) {
    Mutation mutation = newMutation(name, properties, false);
    AgentDefinition definition = new AgentDefinition();
    definition.setType(AgentDefinitionType.BUILTIN);
    apply(definition, mutation);
    return definition;
  }

  void update(AgentDefinition definition, AgentDefinitionEditablePropertiesDTO properties) {
    // 内置 Agent 允许保持/清空未配置模型；用户 Agent 仍要求模型。
    boolean modelRequired = definition.getType() != AgentDefinitionType.BUILTIN;
    apply(definition, newMutation(definition.getName(), properties, modelRequired));
  }

  private void apply(AgentDefinition definition, Mutation mutation) {
    definition.setName(mutation.name());
    definition.setDescription(mutation.description());
    definition.setSystemPrompt(mutation.systemPrompt());
    definition.setModelProviderName(mutation.modelProviderName());
    definition.setModelName(mutation.modelName());
    definition.setVariant(mutation.variant());
    definition.setConfigJson(mutation.configJson());
  }

  private Mutation newMutation(
      String name, AgentDefinitionEditablePropertiesDTO properties, boolean modelRequired) {
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
    if (normalizedName.indexOf('/') >= 0) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not contain '/'");
    }
    ModelRef modelRef = parseModelRef(properties.getModel(), modelRequired);
    String description = editableSupport.trimToNull(properties.getDescription());
    String systemPrompt = editableSupport.trimToNull(properties.getSystemPrompt());
    // null/blank = 不覆盖；runtime/thread 应用时解析 model.defaultVariant。
    String variant = editableSupport.trimToNull(properties.getVariant());
    if (modelRef == null && variant != null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " variant requires a configured model");
    }
    editableSupport.validateMaxLength(RESOURCE, "name", normalizedName, NAME_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "variant", variant, VARIANT_MAX_LENGTH);
    AgentDefinitionConfigDTO config = properties.getConfig();
    if (config == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " config must not be null");
    }
    String configJson;
    try {
      configJson = configCodec.encode(config);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(
        normalizedName,
        description,
        systemPrompt,
        modelRef == null ? null : modelRef.providerName(),
        modelRef == null ? null : modelRef.modelName(),
        variant,
        configJson);
  }

  /** {@code required} 为 false 时 null/空白表示显式未配置模型，返回 null；其余情况严格解析。 */
  private static ModelRef parseModelRef(String raw, boolean required) {
    if (!required && (raw == null || raw.isBlank())) {
      return null;
    }
    try {
      return ModelRef.parse(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "model must identify providerName/modelName", error);
    }
  }

  record Mutation(
      String name,
      String description,
      String systemPrompt,
      String modelProviderName,
      String modelName,
      String variant,
      String configJson) {}
}
