package fun.fengwk.kkstudio.platform.catalog.definition.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgentDefinitions;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentDefinitionConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.service.AgentDefinitionService;
import fun.fengwk.kkstudio.platform.catalog.definition.service.converter.AgentDefinitionConverter;
import fun.fengwk.kkstudio.platform.catalog.definition.service.model.AgentDefinition;
import fun.fengwk.kkstudio.platform.catalog.model.runtime.AgentModelDefaultVariantResolver;
import fun.fengwk.kkstudio.platform.catalog.model.service.model.AgentModel;
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
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType;
import fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind;

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
  private final PluginAgentDependencies pluginAgentDependencies;

  @Override
  public Page<AgentDefinitionDTO> pageAgents(PageQuery pageQuery) {
    return agentDefinitionRepository.page(pageQuery).map(agentDefinitionConverter::convert);
  }

  @Override
  @Transactional
  public AgentDefinitionDTO createAgent(AgentDefinitionCreateDTO createDTO) {
    String name = createDTO == null ? null : createDTO.getName();
    rejectReservedName(name);
    String modelName = parseModelName(createDTO == null ? null : createDTO.getModel(), true);
    referenceResolver.requireModelForUpdate(modelName);
    AgentDefinition definition = definitionMutationFactory.newUserAgent(name, createDTO);
    validateSelection(modelName, definition.getVariant());
    AgentDefinitionConfigDTO config = configCodec.decode(definition.getConfigJson());
    validateConfig(config);
    referenceResolver.requireReferencedLifecycles(config.getSkills(), config.getTools());
    referenceResolver.requireSubagentsForCreate(definition.getName(), config.getSubagents());
    try {
      if (!agentDefinitionRepository.create(definition)) {
        throw new IllegalStateException("create agent definition failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          RESOURCE, "agent definition name already exists: " + definition.getName(), error);
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(MODEL_RESOURCE);
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
    AgentDefinition current = referenceResolver.requireAgent(name);
    ensureExpectedVersion(current, name, rawExpected, expected);
    // 内置 Agent 允许显式未配置模型；用户 Agent 仍要求模型。
    boolean builtin = current.getType() == AgentDefinitionType.BUILTIN;
    String modelName = parseModelName(updateDTO.getModel(), !builtin);
    if (modelName != null) {
      referenceResolver.requireModelForUpdate(modelName);
    }
    AgentDefinition definition = copy(current);
    definitionMutationFactory.update(definition, updateDTO);
    if (modelName != null) {
      validateSelection(modelName, definition.getVariant());
    }
    AgentDefinitionConfigDTO config = configCodec.decode(definition.getConfigJson());
    validateConfig(config);
    referenceResolver.requireReferencedLifecycles(config.getSkills(), config.getTools());
    AgentDefinition locked =
        referenceResolver.requireAgentAndSubagentsForUpdate(name, config.getSubagents());
    ensureExpectedVersion(locked, name, rawExpected, expected);
    try {
      if (!agentDefinitionRepository.updateByName(definition, expected)) {
        AgentDefinition reread = agentDefinitionRepository.getByName(name);
        if (reread == null) {
          throw new AiResourceNotFoundException(RESOURCE);
        }
        throw new AiVersionConflictException(
            RESOURCE, rawExpected, CatalogVersions.format(reread.getVersion()));
      }
    } catch (DataIntegrityViolationException error) {
      if (PostgresqlIntegrityViolationClassifier.isForeignKeyViolation(error)) {
        throw new AiResourceNotFoundException(MODEL_RESOURCE);
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
    if (definition.getType() == AgentDefinitionType.BUILTIN) {
      // 内置 Agent 的名称与存在性由系统持有，用户不能删除。
      throw new AiInUseException(RESOURCE, "built-in agent cannot be deleted: " + name);
    }
    ensureExpectedVersion(definition, name, expectedVersion, expected);
    referenceResolver.ensureNotReferencedAsSubagent(name);
    // 权威 desired manifest 中任一已安装插件的普通配置声明引用该 Agent 即不可删除：删除先持自身行锁，引用方保存先持同锁，
    // 因此并发两侧最多一方成功；只读 desired 事实，不读取已应用状态、不解密秘密、不猜字段。
    if (!pluginAgentDependencies.pluginsDeclaringAgent(name).isEmpty()) {
      throw new AiInUseException(
          RESOURCE, RESOURCE + " is referenced by plugin configuration: " + name);
    }
    if (!agentDefinitionRepository.deleteByName(name, expected)) {
      AgentDefinition reread = agentDefinitionRepository.getByName(name);
      if (reread == null) {
        throw new AiResourceNotFoundException(RESOURCE);
      }
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
    }
  }

  private static AgentDefinition copy(AgentDefinition source) {
    AgentDefinition copy = new AgentDefinition();
    copy.setName(source.getName());
    copy.setType(source.getType());
    copy.setDescription(source.getDescription());
    copy.setSystemPrompt(source.getSystemPrompt());
    copy.setModelName(source.getModelName());
    copy.setVariant(source.getVariant());
    copy.setConfigJson(source.getConfigJson());
    copy.setVersion(source.getVersion());
    copy.setCreateTime(source.getCreateTime());
    copy.setUpdateTime(source.getUpdateTime());
    return copy;
  }

  private static void ensureExpectedVersion(
      AgentDefinition definition, String name, String expectedVersion, long expected) {
    if (definition.getVersion() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(definition.getVersion()));
    }
  }

  /** {@code required} 为 false 时 null/空白表示内置 Agent 的显式未配置模型，返回 null。 */
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
    if (raw.length() > 128) {
      throw new AiValidationException(RESOURCE, "model must be <= 128 characters");
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

  private void validateSelection(String modelName, String variant) {
    AgentModel model = referenceResolver.requireModel(modelName);
    if (model.getKind() == AgentModelKind.GROUP) {
      if (variant != null && !variant.isBlank()) {
        throw new AiValidationException(
            RESOURCE, "variant must be blank when the selected model is a GROUP");
      }
      return;
    }
    if (variant != null && !variant.isBlank()) {
      try {
        variantResolver.resolve(modelName, variant);
      } catch (IllegalArgumentException error) {
        throw new AiValidationException(RESOURCE, error.getMessage(), error);
      }
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
