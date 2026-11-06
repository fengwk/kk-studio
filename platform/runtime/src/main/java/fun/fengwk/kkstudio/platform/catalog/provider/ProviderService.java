package fun.fengwk.kkstudio.platform.catalog.provider;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.ProviderModelDiscovery;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 全局 provider CRUD：输入规范化、名称唯一、(name, version) CAS、删除引用检查与远端模型发现。
 *
 * <p>公开 DTO 永不携带凭据；{@code name} 与 {@code providerType} 存续期间不可变，硬删除后可同名重建。换协议即新建 Provider，再让模型改指向它。
 */
@Service
public class ProviderService {

  private static final String RESOURCE = "provider";
  private static final int NAME_MAX_LENGTH = 64;
  private static final int BASE_URL_MAX_LENGTH = 512;
  private static final int CREDENTIAL_MAX_LENGTH = 512;

  private final ProviderRepository repository;
  private final ProviderConfigurationCodec configurationCodec;
  private final AgentEditableSupport editableSupport;
  private final ProviderModelDiscovery modelDiscovery;

  public ProviderService(
      ProviderRepository repository,
      ProviderConfigurationCodec configurationCodec,
      AgentEditableSupport editableSupport,
      ProviderModelDiscovery modelDiscovery) {
    this.repository = repository;
    this.configurationCodec = configurationCodec;
    this.editableSupport = editableSupport;
    this.modelDiscovery = modelDiscovery;
  }

  public List<ProviderDTO> listProviders() {
    return repository.listAll().stream().map(this::toDto).toList();
  }

  @Transactional
  public ProviderDTO createProvider(ProviderCreateDTO createDTO) {
    if (createDTO == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    Mutation mutation = mutation(createDTO, null);
    return insertProvider(mutation, createDTO.getCredential());
  }

  /** 基于 (name, {@code updateDTO.expectedVersion}) 的原子 CAS 整体替换；凭据按 {@link SecretUpdate} 三态处理。 */
  @Transactional
  public ProviderDTO updateProvider(String name, ProviderUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    Long expected = updateDTO.getExpectedVersion();
    if (expected == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    Provider provider = requireProvider(name);
    ensureExpectedVersion(provider, expected);
    String credential = applyCredential(updateDTO.getCredential(), provider.credential());
    Mutation mutation = mutation(updateDTO, provider.providerType());
    return persistUpdate(applyEdits(provider, mutation, credential), expected);
  }

  /**
   * 按名称的配置导入 upsert：同名已存在时以文件事实整体覆盖，不存在时创建。
   *
   * <p>凭据与更新同一套字段语义：文件中的 null 表示无凭据，非空值覆盖现有凭据。{@code providerType}
   * 只在创建时设置；同名但类型不同直接拒绝，避免把既有模型接到另一协议上。
   */
  @Transactional
  public ProviderDTO importProvider(ProviderCreateDTO spec) {
    if (spec == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    String name = requireName(spec.getName());
    Provider existing = repository.getByName(name);
    if (existing == null) {
      return insertProvider(mutation(spec, null), spec.getCredential());
    }
    if (existing.providerType() != spec.getProviderType()) {
      throw typeConflict(name, existing.providerType(), spec.getProviderType());
    }
    long expected = existing.version();
    Mutation mutation = mutation(spec, existing.providerType());
    return persistUpdate(
        applyEdits(existing, mutation, importCredential(spec.getCredential())), expected);
  }

  /** 与导入写入共用静态校验，不生成连接身份或写入数据库。 */
  public void validateImport(ProviderCreateDTO spec) {
    if (spec == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " body must not be null");
    }
    mutation(spec, null);
  }

  /** 基于 (name, expectedVersion) 的原子 CAS 删除；仍被模型引用时拒绝。 */
  @Transactional
  public void deleteProvider(String name, long expected) {
    Provider provider = repository.getByNameForUpdate(name);
    if (provider == null) {
      throw DomainException.notFound(RESOURCE);
    }
    ensureExpectedVersion(provider, expected);
    if (repository.hasModels(name)) {
      throw DomainException.inUse(RESOURCE, RESOURCE + " in use by models: " + name);
    }
    if (!repository.deleteByName(name, expected)) {
      throw casFailure(name, expected);
    }
  }

  /** 用已保存的连接配置向 Provider 查询当前公布的模型 id（去重排序）。不在事务内：先读取配置，再发起有超时的上游请求。 */
  public List<String> listRemoteModelIds(String name) {
    Provider provider = requireProvider(name);
    try {
      return modelDiscovery.listModelIds(
          provider.providerType(), provider.baseUrl(), provider.credential());
    } catch (IllegalArgumentException invalid) {
      throw DomainException.validation(RESOURCE, invalid.getMessage());
    } catch (ProviderModelDiscovery.Failure failure) {
      throw DomainException.upstream(RESOURCE, failure.getMessage());
    }
  }

  private ProviderDTO insertProvider(Mutation mutation, String credential) {
    String normalizedCredential = editableSupport.trimToNull(credential);
    editableSupport.validateMaxLength(
        RESOURCE, "credential", normalizedCredential, CREDENTIAL_MAX_LENGTH);
    Provider provider =
        Provider.builder()
            .name(mutation.name())
            .description(mutation.description())
            .providerType(mutation.providerType())
            .baseUrl(mutation.baseUrl())
            .credential(normalizedCredential)
            .configJson(mutation.configJson())
            .connectionGenerationId(UUID.randomUUID())
            .build();
    try {
      if (!repository.create(provider)) {
        throw new IllegalStateException("create provider failed");
      }
    } catch (DuplicateKeyException error) {
      // 创建直接依赖 name 主键的唯一约束；同名已存在时数据库拒绝，不预先查询。
      throw DomainException.duplicate(
          RESOURCE, "provider name already exists: " + provider.name(), error);
    }
    return toDto(repository.getByName(provider.name()));
  }

  private ProviderDTO persistUpdate(Provider provider, long expected) {
    if (!repository.updateByName(provider, expected)) {
      throw casFailure(provider.name(), expected);
    }
    return toDto(repository.getByName(provider.name()));
  }

  /** CAS 落空后重读：行已消失为未找到，否则为版本冲突。 */
  private RuntimeException casFailure(String name, long expected) {
    Provider reread = repository.getByName(name);
    if (reread == null) {
      return DomainException.notFound(RESOURCE);
    }
    return DomainException.versionConflict(RESOURCE, expected, reread.version());
  }

  private Provider requireProvider(String name) {
    Provider provider = repository.getByName(name);
    if (provider == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return provider;
  }

  private static void ensureExpectedVersion(Provider provider, long expected) {
    if (provider.version() != expected) {
      throw DomainException.versionConflict(RESOURCE, expected, provider.version());
    }
  }

  /** 凭据只在 {@code baseUrl} 或凭据本身变化时轮换连接世代。 */
  private Provider applyEdits(Provider provider, Mutation mutation, String credential) {
    boolean connectionChanged =
        !Objects.equals(provider.baseUrl(), mutation.baseUrl())
            || !Objects.equals(provider.credential(), credential);
    UUID generationId =
        provider.connectionGenerationId() == null || connectionChanged
            ? UUID.randomUUID()
            : provider.connectionGenerationId();
    return provider.toBuilder()
        .description(mutation.description())
        .baseUrl(mutation.baseUrl())
        .credential(credential)
        .configJson(mutation.configJson())
        .connectionGenerationId(generationId)
        .build();
  }

  private static String applyCredential(SecretUpdate update, String current) {
    if (update == null) {
      throw DomainException.validation(RESOURCE, "credential is required");
    }
    return switch (update.operation()) {
      case KEEP -> current;
      case CLEAR -> null;
      case SET -> update.value();
    };
  }

  /** 配置导入按文件事实写入凭据：null 表示无凭据，非空值即新凭据。 */
  private static String importCredential(String fileValue) {
    return fileValue == null || fileValue.isBlank() ? null : fileValue;
  }

  private static DomainException typeConflict(
      String name, ProviderType current, ProviderType requested) {
    return DomainException.conflict(
        "PROVIDER_TYPE_IMMUTABLE",
        "provider "
            + name
            + " was created as "
            + current.wireValue()
            + " and cannot become "
            + requested.wireValue());
  }

  private Mutation mutation(ProviderCreateDTO properties, ProviderType fixedType) {
    String name = requireName(properties.getName());
    ProviderType providerType = properties.getProviderType();
    if (providerType == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " providerType is required");
    }
    if (fixedType != null && fixedType != providerType) {
      throw typeConflict(name, fixedType, providerType);
    }
    return mutation(
        name,
        providerType,
        properties.getDescription(),
        properties.getBaseUrl(),
        properties.getModelCallTimeoutMillis(),
        properties.getModelCallIdleTimeoutMillis(),
        properties.getModelHttpRetryStatusCodes());
  }

  private Mutation mutation(ProviderUpdateDTO properties, ProviderType existingType) {
    return mutation(
        null,
        existingType,
        properties.getDescription(),
        properties.getBaseUrl(),
        properties.getModelCallTimeoutMillis(),
        properties.getModelCallIdleTimeoutMillis(),
        properties.getModelHttpRetryStatusCodes());
  }

  private Mutation mutation(
      String canonicalName,
      ProviderType providerType,
      String rawDescription,
      String rawBaseUrl,
      Long modelCallTimeoutMillis,
      Long modelCallIdleTimeoutMillis,
      List<Integer> modelHttpRetryStatusCodes) {
    String name = canonicalName == null ? null : requireName(canonicalName);
    String description = editableSupport.trimToNull(rawDescription);
    String baseUrl = editableSupport.trimToNull(rawBaseUrl);
    editableSupport.validateMaxLength(RESOURCE, "baseUrl", baseUrl, BASE_URL_MAX_LENGTH);
    try {
      String configJson =
          configurationCodec.mergeTimeoutPolicy(
              null, modelCallTimeoutMillis, modelCallIdleTimeoutMillis);
      configJson =
          configurationCodec.mergeHttpRetryStatusCodes(configJson, modelHttpRetryStatusCodes);
      return new Mutation(name, description, providerType, baseUrl, configJson);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, error.getMessage());
    }
  }

  private String requireName(String name) {
    if (name == null) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not be blank");
    }
    String canonical = name.strip();
    if (canonical.isEmpty()) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!name.equals(canonical)) {
      throw DomainException.validation(
          RESOURCE, RESOURCE + " name must not contain surrounding whitespace");
    }
    if (canonical.indexOf('/') >= 0) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not contain '/'");
    }
    editableSupport.validateMaxLength(RESOURCE, "name", canonical, NAME_MAX_LENGTH);
    return canonical;
  }

  /** 将 provider 转换为不含凭据的公开 DTO。 */
  private ProviderDTO toDto(Provider provider) {
    ProviderDTO dto = new ProviderDTO();
    dto.setName(provider.name());
    dto.setDescription(provider.description());
    dto.setProviderType(provider.providerType());
    dto.setBaseUrl(provider.baseUrl());
    dto.setCredential(
        SecretConfiguredDTO.of(provider.credential() != null && !provider.credential().isBlank()));
    var timeoutPolicy = configurationCodec.readTimeoutPolicy(provider.configJson());
    dto.setModelCallTimeoutMillis(timeoutPolicy.modelCallTimeout().toMillis());
    dto.setModelCallIdleTimeoutMillis(timeoutPolicy.modelCallIdleTimeout().toMillis());
    dto.setModelHttpRetryStatusCodes(
        configurationCodec.readHttpRetryStatusCodes(provider.configJson()));
    dto.setPromptCacheRetentions(
        provider.providerType().promptCacheRetentions().stream()
            .sorted(Comparator.comparingInt(PromptCacheRetention::ordinal))
            .toList());
    dto.setVersion(provider.version());
    dto.setCreatedAt(provider.createdAt());
    dto.setUpdatedAt(provider.updatedAt());
    return dto;
  }

  private record Mutation(
      String name,
      String description,
      ProviderType providerType,
      String baseUrl,
      String configJson) {}
}
