package fun.fengwk.kkstudio.platform.catalog.definition;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.api.entry.ExecutionSelection;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.platform.catalog.definition.builtin.BuiltinAgents;
import fun.fengwk.kkstudio.platform.catalog.definition.configuration.AgentConfigCodec;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentEditablePropertiesDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;

import java.util.List;
import java.util.Objects;

/**
 * 全局 Agent CRUD：字段与结构化执行配置规范化、Model / Skill / MCP / subagent 引用的有序加锁校验、(name, version) CAS
 * 与删除前的引用检查。
 *
 * <p>内置 Agent 的名称与存在性由系统持有：用户不能同名创建或删除，但可以编辑，且允许显式未配置 execution。
 */
@Service
public class AgentService {

  private static final String RESOURCE = "agent";
  private static final int NAME_MAX_LENGTH = 64;
  private static final StrictJson JSON = StrictJson.standard();

  private final AgentRepository repository;
  private final AgentReferenceResolver referenceResolver;
  private final DriverRegistry driverRegistry;
  private final BuiltinAgentExecutionValidator builtinExecutionValidator;
  private final AgentConfigValidator configValidator;
  private final AgentConfigCodec configCodec;
  private final AgentEditableSupport editableSupport;
  private final PluginAgentDependencies pluginAgentDependencies;
  private final List<AgentReferenceCheck> productReferences;

  public AgentService(
      AgentRepository repository,
      AgentReferenceResolver referenceResolver,
      DriverRegistry driverRegistry,
      BuiltinAgentExecutionValidator builtinExecutionValidator,
      AgentConfigValidator configValidator,
      AgentConfigCodec configCodec,
      AgentEditableSupport editableSupport,
      PluginAgentDependencies pluginAgentDependencies,
      List<AgentReferenceCheck> productReferences) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.referenceResolver = Objects.requireNonNull(referenceResolver, "referenceResolver");
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
    this.builtinExecutionValidator =
        Objects.requireNonNull(builtinExecutionValidator, "builtinExecutionValidator");
    this.configValidator = Objects.requireNonNull(configValidator, "configValidator");
    this.configCodec = Objects.requireNonNull(configCodec, "configCodec");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.pluginAgentDependencies =
        Objects.requireNonNull(pluginAgentDependencies, "pluginAgentDependencies");
    this.productReferences = List.copyOf(productReferences);
  }

  public List<AgentDTO> listAgents() {
    return repository.listAll().stream().map(this::toDto).toList();
  }

  @Transactional
  public AgentDTO createAgent(AgentCreateDTO createDTO) {
    String name = createDTO == null ? null : createDTO.getName();
    rejectReservedName(name);
    Mutation mutation = mutation(name, createDTO, true);
    Agent definition = mutation.applyTo(Agent.builder().type(AgentType.USER)).build();
    builtinExecutionValidator.validateCatalog(definition.execution());
    AgentConfigDTO config = configCodec.decode(definition.configJson());
    validateConfig(config);
    referenceResolver.requireReferencedLifecycles(config.getSkills(), config.getTools());
    referenceResolver.requireSubagentsForCreate(definition.name(), config.getSubagents());
    try {
      if (!repository.create(definition)) {
        throw new IllegalStateException("create agent failed");
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          RESOURCE, "agent name already exists: " + definition.name(), error);
    }
    return toDto(repository.getByName(definition.name()));
  }

  @Transactional
  public AgentDTO updateAgent(String name, AgentUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    Agent current = referenceResolver.requireAgent(name);
    ensureExpectedVersion(current, rawExpected, expected);
    // 内置 Agent 允许显式未配置 execution。
    boolean executionRequired = current.type() != AgentType.BUILTIN;
    Agent definition =
        mutation(current.name(), updateDTO, executionRequired).applyTo(current.toBuilder()).build();
    builtinExecutionValidator.validateCatalog(definition.execution());
    AgentConfigDTO config = configCodec.decode(definition.configJson());
    validateConfig(config);
    referenceResolver.requireReferencedLifecycles(config.getSkills(), config.getTools());
    Agent locked = referenceResolver.requireAgentAndSubagentsForUpdate(name, config.getSubagents());
    ensureExpectedVersion(locked, rawExpected, expected);
    if (!repository.updateByName(definition, expected)) {
      Agent reread = repository.getByName(name);
      if (reread == null) {
        throw DomainException.notFound(RESOURCE);
      }
      throw DomainException.versionConflict(
          RESOURCE, rawExpected, CatalogVersions.format(reread.version()));
    }
    return toDto(repository.getByName(name));
  }

  @Transactional
  public void deleteAgent(String name, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    Agent definition = referenceResolver.requireAgentForUpdate(name);
    if (definition.type() == AgentType.BUILTIN) {
      // 内置 Agent 的名称与存在性由系统持有，用户不能删除。
      throw DomainException.inUse(RESOURCE, "built-in agent cannot be deleted: " + name);
    }
    ensureExpectedVersion(definition, expectedVersion, expected);
    referenceResolver.ensureNotReferencedAsSubagent(name);
    for (AgentReferenceCheck reference : productReferences) {
      if (reference.isReferenced(name)) {
        throw DomainException.inUse(RESOURCE, RESOURCE + " is referenced by a product: " + name);
      }
    }
    // 权威 desired manifest 中任一已安装插件的普通配置声明引用该 Agent 即不可删除：删除先持自身行锁，引用方保存先持同锁，
    // 因此并发两侧最多一方成功；只读 desired 事实，不读取已应用状态、不解密秘密、不猜字段。
    if (!pluginAgentDependencies.pluginsDeclaringAgent(name).isEmpty()) {
      throw DomainException.inUse(
          RESOURCE, RESOURCE + " is referenced by plugin configuration: " + name);
    }
    if (!repository.deleteByName(name, expected)) {
      Agent reread = repository.getByName(name);
      if (reread == null) {
        throw DomainException.notFound(RESOURCE);
      }
      throw DomainException.versionConflict(
          RESOURCE, expectedVersion, CatalogVersions.format(reread.version()));
    }
  }

  /** 与写入共用字段和 execution 形状校验；导入预检不访问尚未导入的模型。 */
  public void validateImport(String name, AgentEditablePropertiesDTO properties) {
    mutation(name, properties, true);
  }

  /** 将 Agent 投影为公开 DTO。 */
  public AgentDTO toDto(Agent definition) {
    AgentDTO dto = new AgentDTO();
    dto.setName(definition.name());
    dto.setType(definition.type());
    dto.setDescription(definition.description());
    dto.setSystemPrompt(definition.systemPrompt());
    dto.setExecution(toExecutionDto(definition.execution()));
    dto.setConfig(configCodec.decode(definition.configJson()));
    dto.setVersion(CatalogVersions.format(definition.version()));
    dto.setCreatedAt(definition.createTime());
    dto.setUpdatedAt(definition.updateTime());
    return dto;
  }

  private static void ensureExpectedVersion(
      Agent definition, String expectedVersion, long expected) {
    if (definition.version() != expected) {
      throw DomainException.versionConflict(
          RESOURCE, expectedVersion, CatalogVersions.format(definition.version()));
    }
  }

  private static void rejectReservedName(String name) {
    if (name == null) {
      return;
    }
    String normalized = name.strip();
    if (BuiltinAgents.isReservedName(normalized)) {
      // 内置 Agent 的身份由系统持有：用户不能以同名创建自己的 Agent 覆盖它。
      throw DomainException.duplicate(
          RESOURCE, "agent name is reserved for a built-in agent: " + normalized);
    }
  }

  private void validateConfig(AgentConfigDTO config) {
    try {
      configValidator.validate(config);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }
  }

  private Mutation mutation(
      String name, AgentEditablePropertiesDTO properties, boolean executionRequired) {
    if (properties == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    if (name == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not be blank");
    }
    String normalizedName = name.strip();
    if (normalizedName.isEmpty()) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(normalizedName)) {
      throw DomainException.validation(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    if (normalizedName.indexOf('/') >= 0) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not contain '/'");
    }
    ExecutionSelection execution = parseExecution(properties.getExecution(), executionRequired);
    String description = editableSupport.trimToNull(properties.getDescription());
    String systemPrompt = editableSupport.trimToNull(properties.getSystemPrompt());
    editableSupport.validateMaxLength(RESOURCE, "name", normalizedName, NAME_MAX_LENGTH);
    AgentConfigDTO config = properties.getConfig();
    if (config == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " config must not be null");
    }
    String configJson;
    try {
      configJson = configCodec.encode(config);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }
    return new Mutation(normalizedName, description, systemPrompt, execution, configJson);
  }

  private ExecutionSelection parseExecution(HarnessExecutionSelectionDTO dto, boolean required) {
    if (dto == null) {
      if (required) {
        throw DomainException.validation(RESOURCE, "execution is required");
      }
      return null;
    }
    if (dto.getDriver() == null || dto.getSettings() == null || !dto.getSettings().isObject()) {
      throw DomainException.validation(RESOURCE, "execution driver and settings are required");
    }
    ExecutionSelection selection;
    try {
      selection =
          new ExecutionSelection(
              dto.getDriver(), JSON.write(dto.getSettings(), "execution.settings"));
      driverRegistry.validate(selection);
    } catch (IllegalArgumentException | IllegalStateException error) {
      throw DomainException.validation(RESOURCE, error.getMessage(), error);
    }
    builtinExecutionValidator.validateShape(selection);
    return selection;
  }

  private static HarnessExecutionSelectionDTO toExecutionDto(ExecutionSelection execution) {
    if (execution == null) {
      return null;
    }
    HarnessExecutionSelectionDTO dto = new HarnessExecutionSelectionDTO();
    dto.setDriver(execution.driver());
    dto.setSettings(JSON.read(execution.settingsJson(), JsonNode.class, "execution.settings"));
    return dto;
  }

  private record Mutation(
      String name,
      String description,
      String systemPrompt,
      ExecutionSelection execution,
      String configJson) {

    Agent.AgentBuilder applyTo(Agent.AgentBuilder builder) {
      return builder
          .name(name)
          .description(description)
          .systemPrompt(systemPrompt)
          .execution(execution)
          .configJson(configJson);
    }
  }
}
