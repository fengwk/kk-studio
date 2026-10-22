package fun.fengwk.kkstudio.platform.catalog.definition;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.model.AgentModel;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.persistence.PostgresqlIntegrityViolationClassifier;
import fun.fengwk.kkstudio.platform.plugin.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

import java.util.Objects;

/**
 * 全局 Agent definition CRUD：字段与结构化执行配置规范化、Model / Skill / MCP / subagent 引用的有序加锁校验、(name, version)
 * CAS 与删除前的引用检查。
 *
 * <p>内置 Agent 的名称与存在性由系统持有：用户不能同名创建或删除，但可以编辑，且允许显式未配置模型。
 */
@Service
public class AgentDefinitionService {

  private static final String RESOURCE = "agent_definition";
  private static final String MODEL_RESOURCE = "agent_model";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int VARIANT_MAX_LENGTH = 64;
  private static final int MODEL_NAME_MAX_LENGTH = 128;

  private final AgentDefinitionRepository repository;
  private final AgentDefinitionReferenceResolver referenceResolver;
  private final AgentModelDefaultVariantResolver variantResolver;
  private final AgentDefinitionConfigValidator configValidator;
  private final AgentDefinitionConfigCodec configCodec;
  private final AgentEditableSupport editableSupport;
  private final PluginAgentDependencies pluginAgentDependencies;

  public AgentDefinitionService(
      AgentDefinitionRepository repository,
      AgentDefinitionReferenceResolver referenceResolver,
      AgentModelDefaultVariantResolver variantResolver,
      AgentDefinitionConfigValidator configValidator,
      AgentDefinitionConfigCodec configCodec,
      AgentEditableSupport editableSupport,
      PluginAgentDependencies pluginAgentDependencies) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver");
    this.variantResolver = Objects.requireNonNull(variantResolver, "variantResolver");
    this.configValidator = Objects.requireNonNull(configValidator, "configValidator");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.pluginAgentDependencies =
        Objects.requireNonNull(pluginAgentDependencies, "pluginAgentDependencies");
  }

  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return repository.page(pageQuery).map(this::toDto);
  }

  @Transactional
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    String name = createDTO == null ? null : createDTO.getName();
    rejectReservedName(name);
    String modelName = parseModelName(createDTO == null ? null : createDTO.getModel(), true);
    AgentModel model = referenceResolver.requireModelForUpdate(modelName);
    Mutation mutation = mutation(name, createDTO, true);
    AgentDefinition definition =
        mutation.applyTo(AgentDefinition.builder().type(AgentDefinitionType.USER)).build();
    validateSelection(model, definition.variant());
    AgentDefinitionConfigDTO config = configCodec.decode(definition.configJson());
    validateConfig(config);
    referenceResolver.requireReferencedLifecycles(config.getSkills(), config.getTools());
    referenceResolver.requireSubagentsForCreate(definition.name(), config.getSubagents());
    try {
      if (!repository.create(definition)) {
        throw new IllegalStateException("create agent definition failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent definition name already exists: " + definition.name(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(MODEL_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByName(definition.name()));
  }

  @Transactional
  public AgentDefinitionDTO updateAgent(String name, AgentDefinitionUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentDefinition current = referenceResolver.requireAgent(name);
    ensureExpectedVersion(current, rawExpected, expected);
    // 内置 Agent 允许显式未配置模型；用户 Agent 仍要求模型。
    boolean modelRequired = current.type() != AgentDefinitionType.BUILTIN;
    String modelName = parseModelName(updateDTO.getModel(), modelRequired);
    AgentModel model =
        modelName == null ? null : referenceResolver.requireModelForUpdate(modelName);
    AgentDefinition definition =
        mutation(current.name(), updateDTO, modelRequired).applyTo(current.toBuilder()).build();
    if (model != null) {
      validateSelection(model, definition.variant());
    }
    AgentDefinitionConfigDTO config = configCodec.decode(definition.configJson());
    validateConfig(config);
    referenceResolver.requireReferencedLifecycles(config.getSkills(), config.getTools());
    AgentDefinition locked =
        referenceResolver.requireAgentAndSubagentsForUpdate(name, config.getSubagents());
    ensureExpectedVersion(locked, rawExpected, expected);
    try {
      if (!repository.updateByName(definition, expected)) {
        AgentDefinition reread = repository.getByName(name);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, rawExpected, CatalogVersions.format(reread.version()));
      }
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(MODEL_RESOURCE);
      }
      throw error;
    }
    return toDto(repository.getByName(name));
  }

  @Transactional
  public void deleteAgent(String name, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentDefinition definition = referenceResolver.requireAgentForUpdate(name);
    if (definition.type() == AgentDefinitionType.BUILTIN) {
      // 内置 Agent 的名称与存在性由系统持有，用户不能删除。
      throw new AiInUseException(RESOURCE, "built-in agent cannot be deleted: " + name);
    }
    ensureExpectedVersion(definition, expectedVersion, expected);
    referenceResolver.ensureNotReferencedAsSubagent(name);
    // Project 工作流阶段按名称指定执行 Agent；删除它会让后台 Issue Run 在下一次调度时失败，因此先拒绝，由用户改阶段配置或归档 Project。
    if (repository.existsReferencingProjectWorkflow(name)) {
      throw new AiInUseException(
          RESOURCE, RESOURCE + " is referenced by a project workflow stage: " + name);
    }
    // 权威 desired manifest 中任一已安装插件的普通配置声明引用该 Agent 即不可删除：删除先持自身行锁，引用方保存先持同锁，
    // 因此并发两侧最多一方成功；只读 desired 事实，不读取已应用状态、不解密秘密、不猜字段。
    if (!pluginAgentDependencies.pluginsDeclaringAgent(name).isEmpty()) {
      throw new AiInUseException(
          RESOURCE, RESOURCE + " is referenced by plugin configuration: " + name);
    }
    if (!repository.deleteByName(name, expected)) {
      AgentDefinition reread = repository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.version()));
    }
  }

  /** 与写入共用字段和 config 校验，不写数据库；导入的 Agent 一律是用户类型，模型必填。 */
  public void validateImport(String name, AgentDefinitionEditablePropertiesDTO properties) {
    mutation(name, properties, true);
  }

  /** 将 Agent definition 投影为公开 DTO。 */
  public AgentDefinitionDTO toDto(AgentDefinition definition) {
    AgentDefinitionDTO dto = new AgentDefinitionDTO();
    dto.setName(definition.name());
    dto.setType(definition.type());
    dto.setDescription(definition.description());
    dto.setSystemPrompt(definition.systemPrompt());
    dto.setModel(definition.modelName());
    dto.setVariant(definition.variant());
    dto.setConfig(configCodec.decode(definition.configJson()));
    dto.setVersion(CatalogVersions.format(definition.version()));
    dto.setCreateTime(definition.createTime());
    dto.setUpdateTime(definition.updateTime());
    return dto;
  }

  private static void ensureExpectedVersion(
      AgentDefinition definition, String expectedVersion, long expected) {
    if (definition.version() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(definition.version()));
    }
  }

  /** {@code required} 为 false 时 null/空白表示内置 Agent 的显式未配置模型，返回 null；逻辑模型名不得包含首尾空白。 */
  private static String parseModelName(String raw, boolean required) {
    if (!required && (raw == null || raw.isBlank())) {
      return null;
    }
    if (raw == null || raw.isBlank()) {
      throw new AiValidationException(RESOURCE, "model must not be blank");
    }
    if (!raw.equals(raw.strip())) {
      throw new AiValidationException(RESOURCE, "model must not contain surrounding whitespace");
    }
    if (raw.length() > MODEL_NAME_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE, "model must be <= " + MODEL_NAME_MAX_LENGTH + " characters");
    }
    return raw;
  }

  private static void rejectReservedName(String name) {
    if (name == null) {
      return;
    }
    String normalized = name.strip();
    if (BuiltinAgentDefinitions.isReservedName(normalized)) {
      // 内置 Agent 的身份由系统持有：用户不能以同名创建自己的 Agent 覆盖它。
      throw new AiDuplicateException(
          RESOURCE, "agent definition name is reserved for a built-in agent: " + normalized);
    }
  }

  /** 单 MODEL 的显式 Variant 必须由其 config 声明；GROUP 没有组级 Variant。 */
  private void validateSelection(AgentModel model, String variant) {
    if (variant == null || variant.isBlank()) {
      return;
    }
    if (model.kind() == AgentModelKind.GROUP) {
      throw new AiValidationException(
          RESOURCE, "variant must be blank when the selected model is a GROUP");
    }
    try {
      variantResolver.resolve(model.name(), variant);
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

  private Mutation mutation(
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
    String modelName = parseModelName(properties.getModel(), modelRequired);
    String description = editableSupport.trimToNull(properties.getDescription());
    String systemPrompt = editableSupport.trimToNull(properties.getSystemPrompt());
    // null/blank = 不覆盖；runtime/thread 应用时解析 model.defaultVariant（GROUP 必须为 null）。
    String variant = editableSupport.trimToNull(properties.getVariant());
    if (modelName == null && variant != null) {
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
    return new Mutation(normalizedName, description, systemPrompt, modelName, variant, configJson);
  }

  private record Mutation(
      String name,
      String description,
      String systemPrompt,
      String modelName,
      String variant,
      String configJson) {

    AgentDefinition.AgentDefinitionBuilder applyTo(AgentDefinition.AgentDefinitionBuilder builder) {
      return builder
          .name(name)
          .description(description)
          .systemPrompt(systemPrompt)
          .modelName(modelName)
          .variant(variant)
          .configJson(configJson);
    }
  }
}
