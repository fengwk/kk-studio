package fun.fengwk.kkstudio.platform.catalog.provider;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.ProviderModelDiscovery;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiUpstreamException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderEditablePropertiesDTO;
import fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 全局 provider CRUD：输入规范化、名称唯一、(name, version) CAS、删除引用检查与远端模型发现。
 *
 * <p>公开 DTO 永不携带凭据；名称存续期间不可变，硬删除后可同名重建。
 */
@Service
public class AgentProviderService {

  private static final String RESOURCE = "agent_provider";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int BASE_URL_MAX_LENGTH = 512;
  private static final int CREDENTIAL_MAX_LENGTH = 512;

  private final AgentProviderRepository repository;
  private final AgentProviderConfigurationCodec configurationCodec;
  private final AgentEditableSupport editableSupport;
  private final ProviderModelDiscovery modelDiscovery;

  public AgentProviderService(
      AgentProviderRepository repository,
      AgentProviderConfigurationCodec configurationCodec,
      AgentEditableSupport editableSupport,
      ProviderModelDiscovery modelDiscovery) {
    this.repository = repository;
    this.configurationCodec = configurationCodec;
    this.editableSupport = editableSupport;
    this.modelDiscovery = modelDiscovery;
  }

  public Page<AgentProviderDTO> pageProviders(PageQuery pageQuery) {
    return repository.page(pageQuery).map(this::toDto);
  }

  @Transactional
  public AgentProviderDTO createProvider(AgentProviderCreateDTO createDTO) {
    return insertProvider(createDTO == null ? null : createDTO.getName(), createDTO);
  }

  /**
   * 基于 (name, {@code updateDTO.expectedVersion}) 的原子 CAS 更新。null/空白凭据视为保留原值；过期版本抛 {@link
   * AiVersionConflictException}。
   */
  @Transactional
  public AgentProviderDTO updateProvider(String name, AgentProviderUpdateDTO updateDTO) {
    String rawExpected = updateDTO == null ? null : updateDTO.getExpectedVersion();
    if (rawExpected == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    long expected = CatalogVersions.parse(rawExpected, "expectedVersion");
    AgentProvider provider = requireProvider(name);
    ensureExpectedVersion(provider, rawExpected, expected);
    return persistUpdate(applyEdits(provider, updateDTO, true), rawExpected, expected);
  }

  /**
   * 按名称的导入式 upsert：同名已存在时以文件事实整体覆盖，不存在时创建。
   *
   * <p>与普通更新的区别是凭据语义：普通更新把 null/空白视为“保留原值”，导入则视为“当前无凭据”并清空，从而保证导出快照与导入结果一致。
   */
  @Transactional
  public AgentProviderDTO importProvider(
      String name, AgentProviderEditablePropertiesDTO properties) {
    String canonicalName = requireName(name);
    AgentProvider existing = repository.getByName(canonicalName);
    if (existing == null) {
      return insertProvider(canonicalName, properties);
    }
    // 同名覆盖：以读取到的当前版本做 CAS。
    long expected = existing.version();
    return persistUpdate(
        applyEdits(existing, properties, false), CatalogVersions.format(expected), expected);
  }

  /** 与导入写入共用静态校验，不生成连接身份或写入数据库。 */
  public void validateImport(String name, AgentProviderEditablePropertiesDTO properties) {
    mutation(properties, name, null, null, false);
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 删除；仍被模型引用时拒绝。 */
  @Transactional
  public void deleteProvider(String name, String expectedVersion) {
    long expected = CatalogVersions.parse(expectedVersion, "expectedVersion");
    AgentProvider provider = repository.getByNameForUpdate(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    ensureExpectedVersion(provider, expectedVersion, expected);
    if (repository.hasModels(name)) {
      throw new AiInUseException(RESOURCE, RESOURCE + " in use by models: " + name);
    }
    if (!repository.deleteByName(name, expected)) {
      throw casFailure(name, expectedVersion);
    }
  }

  /** 用已保存的连接配置向 Provider 查询当前公布的模型 id（去重排序）。不在事务内：先读取配置，再发起有超时的上游请求。 */
  public List<String> listRemoteModelIds(String name) {
    AgentProvider provider = requireProvider(name);
    try {
      return modelDiscovery.listModelIds(
          provider.providerType(), provider.baseUrl(), provider.credential());
    } catch (IllegalArgumentException invalid) {
      throw new AiValidationException(RESOURCE, invalid.getMessage());
    } catch (ProviderModelDiscovery.Failure failure) {
      throw new AiUpstreamException(RESOURCE, failure.getMessage());
    }
  }

  private AgentProviderDTO insertProvider(
      String name, AgentProviderEditablePropertiesDTO properties) {
    Mutation mutation = mutation(properties, name, null, null, false);
    AgentProvider provider =
        AgentProvider.builder()
            .name(mutation.name())
            .description(mutation.description())
            .providerType(mutation.providerType())
            .baseUrl(mutation.baseUrl())
            .credential(mutation.credential())
            .configJson(mutation.configJson())
            .connectionGenerationId(UUID.randomUUID())
            .build();
    try {
      if (!repository.create(provider)) {
        throw new IllegalStateException("create agent provider failed");
      }
    } catch (DuplicateKeyException error) {
      // 创建直接依赖 name 主键的唯一约束；同名已存在时数据库拒绝，不预先查询。
      throw new AiDuplicateException(
          RESOURCE, "agent provider name already exists: " + provider.name(), error);
    }
    return toDto(repository.getByName(provider.name()));
  }

  private AgentProviderDTO persistUpdate(
      AgentProvider provider, String rawExpected, long expected) {
    if (!repository.updateByName(provider, expected)) {
      throw casFailure(provider.name(), rawExpected);
    }
    return toDto(repository.getByName(provider.name()));
  }

  /** CAS 落空后重读：行已消失为未找到，否则为版本冲突。 */
  private RuntimeException casFailure(String name, String rawExpected) {
    AgentProvider reread = repository.getByName(name);
    if (reread == null) {
      return new AiResourceNotFoundException(RESOURCE);
    }
    return new AiVersionConflictException(
        RESOURCE, rawExpected, CatalogVersions.format(reread.version()));
  }

  private AgentProvider requireProvider(String name) {
    AgentProvider provider = repository.getByName(name);
    if (provider == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return provider;
  }

  private static void ensureExpectedVersion(
      AgentProvider provider, String expectedVersion, long expected) {
    if (provider.version() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, expectedVersion, CatalogVersions.format(provider.version()));
    }
  }

  /** 应用编辑并在协议、凭据或协议配置变化（或缺失世代）时轮换连接世代。 */
  private AgentProvider applyEdits(
      AgentProvider provider,
      AgentProviderEditablePropertiesDTO properties,
      boolean preserveMissingEdits) {
    Mutation mutation =
        mutation(
            properties,
            provider.name(),
            provider.credential(),
            provider.configJson(),
            preserveMissingEdits);
    boolean protocolChanged =
        !Objects.equals(provider.providerType(), mutation.providerType())
            || !Objects.equals(provider.baseUrl(), mutation.baseUrl())
            || !Objects.equals(provider.credential(), mutation.credential())
            || !configurationCodec.isProtocolConfigEqual(
                provider.configJson(), mutation.configJson());
    UUID generationId =
        provider.connectionGenerationId() == null || protocolChanged
            ? UUID.randomUUID()
            : provider.connectionGenerationId();
    return provider.toBuilder()
        .description(mutation.description())
        .providerType(mutation.providerType())
        .baseUrl(mutation.baseUrl())
        .credential(mutation.credential())
        .configJson(mutation.configJson())
        .connectionGenerationId(generationId)
        .build();
  }

  private Mutation mutation(
      AgentProviderEditablePropertiesDTO properties,
      String fallbackName,
      String existingCredential,
      String existingConfigJson,
      boolean preserveMissingEdits) {
    if (properties == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String name = requireName(fallbackName);
    String providerType = properties.getProviderType();
    if (providerType == null || providerType.isBlank()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " providerType must not be blank");
    }
    String credential = editableSupport.trimToNull(properties.getCredential());
    if (preserveMissingEdits && credential == null) {
      credential = existingCredential;
    }
    String description = editableSupport.trimToNull(properties.getDescription());
    String baseUrl = editableSupport.trimToNull(properties.getBaseUrl());
    editableSupport.validateMaxLength(RESOURCE, "baseUrl", baseUrl, BASE_URL_MAX_LENGTH);
    editableSupport.validateMaxLength(RESOURCE, "credential", credential, CREDENTIAL_MAX_LENGTH);
    try {
      String configJson =
          configurationCodec.mergeTimeoutPolicy(
              existingConfigJson,
              properties.getModelCallTimeoutMillis(),
              properties.getModelCallIdleTimeoutMillis());
      // 普通更新省略字段保留既有覆盖；导入与创建按文件/请求事实应用（缺失即视为无覆盖）。
      boolean overrideProvided =
          !preserveMissingEdits || properties.isModelHttpRetryStatusCodesProvided();
      configJson =
          configurationCodec.mergeHttpRetryStatusCodes(
              configJson, overrideProvided, properties.getModelHttpRetryStatusCodes());
      return new Mutation(
          name,
          description,
          ProviderType.fromWireValue(providerType),
          baseUrl,
          credential,
          configJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage());
    }
  }

  private String requireName(String name) {
    if (name == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    String canonical = name.strip();
    if (canonical.isEmpty()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(canonical)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    if (canonical.indexOf('/') >= 0) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not contain '/'");
    }
    editableSupport.validateMaxLength(RESOURCE, "name", canonical, NAME_MAX_LENGTH);
    return canonical;
  }

  /** 将 provider 转换为不含凭据的公开 DTO。 */
  private AgentProviderDTO toDto(AgentProvider provider) {
    AgentProviderDTO dto = new AgentProviderDTO();
    dto.setName(provider.name());
    dto.setDescription(provider.description());
    dto.setProviderType(provider.providerType().wireValue());
    dto.setBaseUrl(provider.baseUrl());
    dto.setConfigured(provider.credential() != null && !provider.credential().isBlank());
    var timeoutPolicy = configurationCodec.readTimeoutPolicy(provider.configJson());
    dto.setModelCallTimeoutMillis(timeoutPolicy.modelCallTimeout().toMillis());
    dto.setModelCallIdleTimeoutMillis(timeoutPolicy.modelCallIdleTimeout().toMillis());
    dto.setModelHttpRetryStatusCodes(
        configurationCodec.readHttpRetryStatusCodes(provider.configJson()));
    dto.setVersion(CatalogVersions.format(provider.version()));
    dto.setCreateTime(provider.createTime());
    dto.setUpdateTime(provider.updateTime());
    return dto;
  }

  private record Mutation(
      String name,
      String description,
      ProviderType providerType,
      String baseUrl,
      String credential,
      String configJson) {}
}
