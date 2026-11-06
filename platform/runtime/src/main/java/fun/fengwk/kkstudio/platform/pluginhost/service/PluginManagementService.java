package fun.fengwk.kkstudio.platform.pluginhost.service;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.plugin.Plugin;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheck;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginFunctionNamesProjection;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthKindDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConfigurationDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO.Authentication;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO.BrowserSession;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO.Reachability;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginDTO;
import fun.fengwk.kkstudio.platform.pluginhost.dto.PluginSecretProjectionDTO;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.pluginhost.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plugin 管理面用例：安全投影、保存配置、{@code DEEP_LINK} 认证的 prepare / complete / disconnect 与显式连接检查。
 *
 * <p>只路由到已安装 Plugin。交互式认证是「一个 Plugin 至多一个 pending attempt」的一次性协议：complete 以单条条件删除原子消费 attempt，并要求
 * prepare 之后配置版本未变、材料 region 与 prepare 选定的 region 一致。回调地址既不落库也不进日志或异常消息。
 */
@Slf4j
public final class PluginManagementService {

  /** 已保存但本节点尚未应用时的固定说明。 */
  public static final String PENDING_APPLICATION_ERROR =
      "configuration is saved but this node has not applied it yet";

  private static final String RESOURCE = "plugin";
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private final PluginRegistry registry;
  private final Supplier<PluginCatalogRuntime> catalogRuntime;
  private final PluginConfigurationRepository configurations;
  private final PluginCredentialRepository credentials;
  private final PluginConfigurationService configurationService;
  private final PluginFunctionNamesProjection functionNamesProjection;
  private final PluginAuthAttemptRepository authAttempts;
  private final Duration authAttemptTtl;
  private final Clock clock;

  public PluginManagementService(
      PluginRegistry registry,
      Supplier<PluginCatalogRuntime> catalogRuntime,
      PluginConfigurationRepository configurations,
      PluginCredentialRepository credentials,
      PluginConfigurationService configurationService,
      PluginFunctionNamesProjection functionNamesProjection,
      PluginAuthAttemptRepository authAttempts,
      Duration authAttemptTtl,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.catalogRuntime = Objects.requireNonNull(catalogRuntime, "catalogRuntime");
    this.configurations = Objects.requireNonNull(configurations, "configurations");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.configurationService =
        Objects.requireNonNull(configurationService, "configurationService");
    this.functionNamesProjection =
        Objects.requireNonNull(functionNamesProjection, "functionNamesProjection");
    this.authAttempts = Objects.requireNonNull(authAttempts, "authAttempts");
    if (authAttemptTtl.isZero() || authAttemptTtl.isNegative()) {
      throw new IllegalArgumentException("authAttemptTtl must be a positive duration");
    }
    this.authAttemptTtl = authAttemptTtl;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 已安装 Plugin 的安全投影，整份列表共享一次当前快照租用。 */
  public List<PluginDTO> listPlugins() {
    PluginCatalogRuntime runtime = catalogRuntime.get();
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      return registry.plugins().stream()
          .map(plugin -> describe(plugin, lease.snapshot(), runtime.applicationError()))
          .toList();
    }
  }

  public PluginDTO getPlugin(String pluginId) {
    return describe(requirePlugin(pluginId));
  }

  public PluginDTO updateConfiguration(
      String pluginId,
      String expectedVersion,
      JsonNode values,
      Map<String, SecretUpdate> secretPatch) {
    long version = parseVersion(expectedVersion);
    Plugin plugin = requirePlugin(pluginId);
    configurationService.configure(
        pluginId, version, valuesJson(values), canonicalPatch(secretPatch));
    return describe(plugin);
  }

  /** 普通配置值由 Plugin schema 决定，转换为有界 JSON 文本后交给唯一写入口。 */
  private static String valuesJson(JsonNode values) {
    String text = BoundedJsonWriter.write(values, PluginConfig.MAX_VALUES_UTF8_BYTES);
    if (text == null) {
      throw DomainException.validation(RESOURCE, "values must match the plugin schema");
    }
    return text;
  }

  /** 秘密补丁的字段名必须是规范的已声明字段名；操作与值由 {@link SecretUpdate} 自身约束，错误不得包含秘密值。 */
  private static Map<String, SecretUpdate> canonicalPatch(Map<String, SecretUpdate> secretPatch) {
    if (secretPatch == null) {
      return Map.of();
    }
    Map<String, SecretUpdate> patch = new LinkedHashMap<>();
    for (Map.Entry<String, SecretUpdate> entry : secretPatch.entrySet()) {
      String field = entry.getKey();
      if (field.isBlank() || !field.equals(field.strip())) {
        throw DomainException.validation(RESOURCE, "secretPatch must use canonical field names");
      }
      if (entry.getValue() == null) {
        throw DomainException.validation(RESOURCE, "secretPatch must declare an operation");
      }
      patch.put(field, entry.getValue());
    }
    return patch;
  }

  /** canonical 非负十进制版本；不可解析即校验失败，错误绝不回显请求值。 */
  private static long parseVersion(String value) {
    try {
      return CatalogVersions.parse(value, "expectedVersion");
    } catch (DomainException error) {
      if (error.code() != DomainErrorCode.VALIDATION) {
        throw error;
      }
      throw DomainException.validation(
          RESOURCE, "expectedVersion must be a canonical non-negative decimal string");
    }
  }

  /** 校验 region、取官方登录地址，再写入本次唯一的 pending attempt。返回值只有 attemptId 与登录地址。 */
  public PluginAuthPrepareDTO prepareAuth(String pluginId, String region) {
    Plugin plugin = requirePlugin(pluginId);
    PluginAuthSpec.DeepLink deepLink = requireDeepLink(plugin);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (!deepLink.acceptsRegion(region)) {
      throw DomainException.validation(RESOURCE, "region must be one of the declared candidates");
    }
    String loginUrl;
    try {
      loginUrl = handler.loginUrl(region);
    } catch (RuntimeException error) {
      log.debug(
          "Plugin {} rejected the selected region: {}", pluginId, error.getClass().getSimpleName());
      throw DomainException.validation(RESOURCE, "plugin rejected the selected region");
    }
    if (loginUrl == null || loginUrl.isBlank()) {
      throw DomainException.validation(RESOURCE, "plugin did not provide a login url");
    }
    String authAttemptId = PluginAuthNonce.issue();
    authAttempts.replace(
        new PluginAuthAttemptRow(
            pluginId,
            PluginAuthNonce.hash(authAttemptId),
            requireConfiguration(pluginId).version(),
            region,
            clock.instant().plus(authAttemptTtl)));
    PluginAuthPrepareDTO response = new PluginAuthPrepareDTO();
    response.setAuthAttemptId(authAttemptId);
    response.setLoginUrl(loginUrl);
    return response;
  }

  /** 原子消费 attempt、调用 Plugin 校验回调，并把材料写为新凭据。 */
  public PluginDTO completeAuth(String pluginId, String authAttemptId, String callbackUrl) {
    Plugin plugin = requirePlugin(pluginId);
    PluginAuthSpec.DeepLink deepLink = requireDeepLink(plugin);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (!PluginAuthNonce.isValidHandle(authAttemptId)) {
      throw DomainException.validation(
          RESOURCE, "authAttemptId must be a canonical attempt handle");
    }
    if (callbackUrl == null || callbackUrl.isBlank()) {
      throw DomainException.validation(RESOURCE, "callbackUrl must not be blank");
    }
    Instant now = clock.instant();
    PluginAuthAttemptRow attempt =
        authAttempts
            .consume(pluginId, PluginAuthNonce.hash(authAttemptId), now)
            .orElseThrow(
                () ->
                    DomainException.validation(
                        RESOURCE, "auth attempt is unknown, expired, superseded or stale"));
    PluginCredentialMaterial material;
    try {
      material = handler.complete(callbackUrl);
    } catch (RuntimeException error) {
      log.debug(
          "Plugin {} rejected the auth callback: {}", pluginId, error.getClass().getSimpleName());
      throw DomainException.validation(RESOURCE, "plugin rejected the auth callback");
    }
    if (material == null
        || !material.interactive()
        || !attempt.region().equals(material.region())
        || !deepLink.acceptsRegion(material.region())) {
      throw DomainException.validation(
          RESOURCE, "plugin returned a credential outside the prepared region");
    }
    if (!material.expiresAt().isAfter(now) || material.nextRefreshAt().isBefore(now)) {
      throw DomainException.validation(RESOURCE, "plugin returned an unusable credential lifetime");
    }
    configurationService.completeAuth(pluginId, material);
    return describe(plugin);
  }

  public void disconnect(String pluginId) {
    configurationService.disconnect(pluginId);
  }

  /** 对本节点当前实例执行一次显式只读连接检查；请求版本必须等于本节点已应用的版本。 */
  public PluginConnectionCheckDTO checkConnection(String pluginId, String expectedVersion) {
    long version = parseVersion(expectedVersion);
    requirePlugin(pluginId);
    try (PluginCatalogRuntime.Lease lease = catalogRuntime.get().acquire()) {
      PluginCatalogSnapshot.ConfiguredPlugin configured =
          applied(lease.snapshot(), pluginId).orElseThrow();
      long appliedVersion = configured.config().revision();
      if (version != appliedVersion) {
        throw DomainException.versionConflict(
            RESOURCE, "version=" + version, "version=" + appliedVersion);
      }
      PluginConnectionCheck connectionCheck =
          configured
              .instance()
              .connectionCheck()
              .orElseThrow(
                  () ->
                      DomainException.validation(
                          RESOURCE, "plugin does not support connection checks"));
      PluginConnectionCheckResult result = connectionCheck.check();
      PluginConnectionCheckDTO dto = new PluginConnectionCheckDTO();
      dto.setPluginId(pluginId);
      dto.setAppliedVersion(Long.toString(appliedVersion));
      dto.setCheckedAt(clock.instant());
      dto.setReachability(Reachability.valueOf(result.reachability().name()));
      dto.setAuthentication(Authentication.valueOf(result.authentication().name()));
      dto.setBrowserSession(BrowserSession.valueOf(result.browserSession().name()));
      return dto;
    }
  }

  private PluginDTO describe(Plugin plugin) {
    PluginCatalogRuntime runtime = catalogRuntime.get();
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      return describe(plugin, lease.snapshot(), runtime.applicationError());
    }
  }

  /** 安全投影：配置取自数据库当前行（desired），能力与已应用版本取自本节点快照；秘密只以说明与布尔出现。 */
  private PluginDTO describe(
      Plugin plugin, PluginCatalogSnapshot snapshot, String applicationError) {
    PluginDescriptor descriptor = plugin.descriptor();
    String pluginId = descriptor.pluginId();
    PluginConfigurationRow desired = requireConfiguration(pluginId);
    Optional<PluginCredentialRow> credential = credentials.find(pluginId);
    Set<String> configuredSecrets =
        configuredSecretFields(plugin, credential.map(PluginCredentialRow::payloadJson));
    PluginDTO dto = new PluginDTO();
    dto.setPluginId(pluginId);
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    PluginAuthKindDTO authKind = authKindOf(plugin.authenticationSpec());
    dto.setAuthKind(authKind);
    if (authKind != null) {
      PluginCredentialProjection projection =
          credential
              .map(
                  row ->
                      new PluginCredentialProjection(
                          row.status(),
                          row.region(),
                          row.expiresAt(),
                          row.nextRefreshAt(),
                          row.lastRefreshedAt(),
                          row.lastRefreshError()))
              .orElseGet(PluginCredentialProjection::notConnected);
      boolean staticKeyMissing =
          plugin.authenticationSpec() instanceof PluginAuthSpec.StaticKey staticKey
              && !configuredSecrets.contains(staticKey.secretField());
      dto.setStatus(staticKeyMissing ? PluginCredentialStatus.NOT_CONNECTED : projection.status());
      dto.setRegion(projection.region());
      dto.setExpiresAt(projection.expiresAt());
      dto.setNextRefreshAt(projection.nextRefreshAt());
      dto.setLastRefreshedAt(projection.lastRefreshedAt());
      dto.setLastRefreshError(projection.lastRefreshError());
    }
    dto.setConfiguration(configurationOf(plugin, desired, configuredSecrets));
    dto.setDesiredVersion(Long.toString(desired.version()));
    Optional<PluginCatalogSnapshot.ConfiguredPlugin> applied = applied(snapshot, pluginId);
    long appliedVersion = applied.map(entry -> entry.config().revision()).orElse(0L);
    dto.setAppliedVersion(applied.isPresent() ? Long.toString(appliedVersion) : null);
    dto.setApplicationError(
        appliedVersion == desired.version() ? null : pendingError(applicationError));
    dto.setConnectionCheckSupported(
        applied.flatMap(entry -> entry.instance().connectionCheck()).isPresent());
    dto.setHarnessToolNames(applied.map(entry -> toolNames(snapshot, entry)).orElse(List.of()));
    dto.setCanvasFunctionNames(
        applied
            .map(entry -> functionNamesProjection.functionNames(snapshot, entry))
            .orElse(List.of()));
    return dto;
  }

  private static String pendingError(String applicationError) {
    return applicationError != null ? applicationError : PENDING_APPLICATION_ERROR;
  }

  private static List<String> toolNames(
      PluginCatalogSnapshot snapshot, PluginCatalogSnapshot.ConfiguredPlugin plugin) {
    List<String> names = new ArrayList<>();
    for (ToolContribution tool : snapshot.harnessCatalog().tools()) {
      if (plugin.contributorIds().contains(tool.id().contributorId())) {
        names.add(tool.definition().descriptor().name());
      }
    }
    return List.copyOf(names);
  }

  private static PluginConfigurationDTO configurationOf(
      Plugin plugin, PluginConfigurationRow desired, Set<String> configuredSecrets) {
    PluginConfigSchema schema = plugin.configurationSchema();
    PluginConfigurationDTO configuration = new PluginConfigurationDTO();
    configuration.setVersion(Long.toString(desired.version()));
    configuration.setValues(JsonValues.readTree(desired.valuesJson()));
    configuration.setValuesSchema(JsonValues.readTree(SCHEMA_CODEC.encode(schema.valuesSchema())));
    configuration.setDefaults(JsonValues.readTree(schema.defaultsJson()));
    Map<String, PluginSecretProjectionDTO> secrets = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : schema.secrets().entrySet()) {
      PluginSecretProjectionDTO secret = new PluginSecretProjectionDTO();
      secret.setDescription(entry.getValue());
      secret.setConfigured(configuredSecrets.contains(entry.getKey()));
      secrets.put(entry.getKey(), secret);
    }
    configuration.setSecrets(secrets);
    return configuration;
  }

  /** 已录入的声明秘密字段名；只有字段名，绝无值。 */
  private static Set<String> configuredSecretFields(Plugin plugin, Optional<String> payload) {
    if (payload.isEmpty()) {
      return Set.of();
    }
    JsonNode node = JsonValues.readTree(payload.get());
    return plugin.configurationSchema().secrets().keySet().stream()
        .filter(node::has)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Optional<PluginCatalogSnapshot.ConfiguredPlugin> applied(
      PluginCatalogSnapshot snapshot, String pluginId) {
    return snapshot.configuredPlugins().stream()
        .filter(entry -> entry.descriptor().pluginId().equals(pluginId))
        .findFirst();
  }

  private PluginConfigurationRow requireConfiguration(String pluginId) {
    return configurations.find(pluginId).orElseThrow(() -> DomainException.notFound(RESOURCE));
  }

  private Plugin requirePlugin(String pluginId) {
    return registry.find(pluginId).orElseThrow(() -> DomainException.notFound(RESOURCE));
  }

  private static PluginAuthHandler requireAuthHandler(Plugin plugin) {
    return plugin
        .authHandler()
        .orElseThrow(
            () ->
                DomainException.validation(
                    RESOURCE, "plugin does not provide interactive authentication"));
  }

  private static PluginAuthSpec.DeepLink requireDeepLink(Plugin plugin) {
    if (plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink deepLink) {
      return deepLink;
    }
    throw DomainException.validation(
        RESOURCE, "plugin does not provide interactive authentication");
  }

  private static PluginAuthKindDTO authKindOf(PluginAuthSpec authSpec) {
    return switch (authSpec) {
      case PluginAuthSpec.DeepLink deepLink -> new PluginAuthKindDTO.DeepLink(deepLink.regions());
      case PluginAuthSpec.StaticKey staticKey -> new PluginAuthKindDTO.StaticKey(
          staticKey.secretField());
      case PluginAuthSpec.None ignored -> null;
    };
  }
}
