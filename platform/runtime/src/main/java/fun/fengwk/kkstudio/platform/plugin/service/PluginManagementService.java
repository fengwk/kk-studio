package fun.fengwk.kkstudio.platform.plugin.service;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheck;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.Authentication;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.BrowserSession;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.Reachability;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginSecretProjectionDTO;

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
 * prepare 之后配置修订未变、材料 region 与 prepare 选定的 region 一致。回调地址既不落库也不进日志或异常消息。
 */
@Slf4j
public final class PluginManagementService {

  /** 已保存但本节点尚未应用时的固定说明。 */
  public static final String PENDING_APPLICATION_ERROR =
      "configuration is saved but this node has not applied it yet";

  private static final String RESOURCE = "plugin";
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private final StudioPluginRegistry registry;
  private final Supplier<PluginCatalogRuntime> catalogRuntime;
  private final PluginConfigurationRepository configurations;
  private final PluginCredentialRepository credentials;
  private final PluginConfigurationService configurationService;
  private final PluginAuthAttemptRepository authAttempts;
  private final Duration authAttemptTtl;
  private final Clock clock;

  public PluginManagementService(
      StudioPluginRegistry registry,
      Supplier<PluginCatalogRuntime> catalogRuntime,
      PluginConfigurationRepository configurations,
      PluginCredentialRepository credentials,
      PluginConfigurationService configurationService,
      PluginAuthAttemptRepository authAttempts,
      Duration authAttemptTtl,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.catalogRuntime = Objects.requireNonNull(catalogRuntime, "catalogRuntime");
    this.configurations = Objects.requireNonNull(configurations, "configurations");
    this.credentials = Objects.requireNonNull(credentials, "credentials");
    this.configurationService =
        Objects.requireNonNull(configurationService, "configurationService");
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
      long expectedRevision,
      String valuesJson,
      Map<String, PluginSecretPatch> secretPatch) {
    StudioPlugin plugin = requirePlugin(pluginId);
    configurationService.configure(pluginId, expectedRevision, valuesJson, secretPatch);
    return describe(plugin);
  }

  /** 校验 region、取官方登录地址，再写入本次唯一的 pending attempt。返回值只有 attemptId 与登录地址。 */
  public PluginAuthPrepareDTO prepareAuth(String pluginId, String region) {
    StudioPlugin plugin = requirePlugin(pluginId);
    PluginAuthSpec.DeepLink deepLink = requireDeepLink(plugin);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (!deepLink.acceptsRegion(region)) {
      throw new AiValidationException(RESOURCE, "region must be one of the declared candidates");
    }
    String loginUrl;
    try {
      loginUrl = handler.loginUrl(region);
    } catch (RuntimeException error) {
      log.debug(
          "Plugin {} rejected the selected region: {}", pluginId, error.getClass().getSimpleName());
      throw new AiValidationException(RESOURCE, "plugin rejected the selected region");
    }
    if (loginUrl == null || loginUrl.isBlank()) {
      throw new AiValidationException(RESOURCE, "plugin did not provide a login url");
    }
    String authAttemptId = PluginAuthNonce.issue();
    authAttempts.replace(
        new PluginAuthAttemptRow(
            pluginId,
            PluginAuthNonce.hash(authAttemptId),
            requireConfiguration(pluginId).revision(),
            region,
            clock.instant().plus(authAttemptTtl)));
    PluginAuthPrepareDTO response = new PluginAuthPrepareDTO();
    response.setAuthAttemptId(authAttemptId);
    response.setLoginUrl(loginUrl);
    return response;
  }

  /** 原子消费 attempt、调用 Plugin 校验回调，并把材料写为新凭据。 */
  public PluginDTO completeAuth(String pluginId, String authAttemptId, String callbackUrl) {
    StudioPlugin plugin = requirePlugin(pluginId);
    PluginAuthSpec.DeepLink deepLink = requireDeepLink(plugin);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (!PluginAuthNonce.isValidHandle(authAttemptId)) {
      throw new AiValidationException(RESOURCE, "authAttemptId must be a canonical attempt handle");
    }
    if (callbackUrl == null || callbackUrl.isBlank()) {
      throw new AiValidationException(RESOURCE, "callbackUrl must not be blank");
    }
    Instant now = clock.instant();
    PluginAuthAttemptRow attempt =
        authAttempts
            .consume(pluginId, PluginAuthNonce.hash(authAttemptId), now)
            .orElseThrow(
                () ->
                    new AiValidationException(
                        RESOURCE, "auth attempt is unknown, expired, superseded or stale"));
    PluginCredentialMaterial material;
    try {
      material = handler.complete(callbackUrl);
    } catch (RuntimeException error) {
      log.debug(
          "Plugin {} rejected the auth callback: {}", pluginId, error.getClass().getSimpleName());
      throw new AiValidationException(RESOURCE, "plugin rejected the auth callback");
    }
    if (material == null
        || !material.interactive()
        || !attempt.region().equals(material.region())
        || !deepLink.acceptsRegion(material.region())) {
      throw new AiValidationException(
          RESOURCE, "plugin returned a credential outside the prepared region");
    }
    if (!material.expiresAt().isAfter(now) || material.nextRefreshAt().isBefore(now)) {
      throw new AiValidationException(RESOURCE, "plugin returned an unusable credential lifetime");
    }
    configurationService.completeAuth(pluginId, material);
    return describe(plugin);
  }

  public void disconnect(String pluginId) {
    configurationService.disconnect(pluginId);
  }

  /** 对本节点当前实例执行一次显式只读连接检查；请求修订必须等于本节点已应用的修订。 */
  public PluginConnectionCheckDTO checkConnection(String pluginId, long expectedRevision) {
    requirePlugin(pluginId);
    try (PluginCatalogRuntime.Lease lease = catalogRuntime.get().acquire()) {
      PluginCatalogSnapshot.ConfiguredPlugin configured =
          applied(lease.snapshot(), pluginId).orElseThrow();
      long appliedRevision = configured.config().revision();
      if (expectedRevision != appliedRevision) {
        throw new AiVersionConflictException(
            RESOURCE, "revision=" + expectedRevision, "revision=" + appliedRevision);
      }
      PluginConnectionCheck connectionCheck =
          configured
              .instance()
              .connectionCheck()
              .orElseThrow(
                  () ->
                      new AiValidationException(
                          RESOURCE, "plugin does not support connection checks"));
      PluginConnectionCheckResult result = connectionCheck.check();
      PluginConnectionCheckDTO dto = new PluginConnectionCheckDTO();
      dto.setPluginId(pluginId);
      dto.setAppliedRevision(Long.toString(appliedRevision));
      dto.setCheckedAt(clock.instant());
      dto.setReachability(Reachability.valueOf(result.reachability().name()));
      dto.setAuthentication(Authentication.valueOf(result.authentication().name()));
      dto.setBrowserSession(BrowserSession.valueOf(result.browserSession().name()));
      return dto;
    }
  }

  private PluginDTO describe(StudioPlugin plugin) {
    PluginCatalogRuntime runtime = catalogRuntime.get();
    try (PluginCatalogRuntime.Lease lease = runtime.acquire()) {
      return describe(plugin, lease.snapshot(), runtime.applicationError());
    }
  }

  /** 安全投影：配置取自数据库当前行（desired），能力与已应用修订取自本节点快照；秘密只以说明与布尔出现。 */
  private PluginDTO describe(
      StudioPlugin plugin, PluginCatalogSnapshot snapshot, String applicationError) {
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
      dto.setStatus(
          staticKeyMissing
              ? PluginCredentialStatus.NOT_CONNECTED.name()
              : projection.status().name());
      dto.setRegion(projection.region());
      dto.setExpiresAt(projection.expiresAt());
      dto.setNextRefreshAt(projection.nextRefreshAt());
      dto.setLastRefreshedAt(projection.lastRefreshedAt());
      dto.setLastRefreshError(projection.lastRefreshError());
    }
    dto.setConfiguration(configurationOf(plugin, desired, configuredSecrets));
    dto.setDesiredRevision(Long.toString(desired.revision()));
    Optional<PluginCatalogSnapshot.ConfiguredPlugin> applied = applied(snapshot, pluginId);
    long appliedRevision = applied.map(entry -> entry.config().revision()).orElse(0L);
    dto.setAppliedRevision(applied.isPresent() ? Long.toString(appliedRevision) : null);
    dto.setApplicationError(
        appliedRevision == desired.revision() ? null : pendingError(applicationError));
    dto.setConnectionCheckSupported(
        applied.flatMap(entry -> entry.instance().connectionCheck()).isPresent());
    dto.setHarnessToolNames(applied.map(entry -> toolNames(snapshot, entry)).orElse(List.of()));
    dto.setCanvasFunctionNames(
        applied.map(entry -> functionNames(snapshot, entry)).orElse(List.of()));
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

  private static List<String> functionNames(
      PluginCatalogSnapshot snapshot, PluginCatalogSnapshot.ConfiguredPlugin plugin) {
    List<String> names = new ArrayList<>();
    for (CanvasFunctionCatalog.RegisteredFunction function :
        snapshot.canvasFunctionCatalog().list()) {
      if (plugin.functionNames().contains(function.function().name())) {
        names.add(function.function().name());
      }
    }
    return List.copyOf(names);
  }

  private static PluginConfigurationDTO configurationOf(
      StudioPlugin plugin, PluginConfigurationRow desired, Set<String> configuredSecrets) {
    PluginConfigSchema schema = plugin.configurationSchema();
    PluginConfigurationDTO configuration = new PluginConfigurationDTO();
    configuration.setValuesSchema(SCHEMA_CODEC.encode(schema.valuesSchema()));
    configuration.setDefaultsJson(schema.defaultsJson());
    configuration.setRevision(Long.toString(desired.revision()));
    configuration.setValuesJson(desired.valuesJson());
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
  private static Set<String> configuredSecretFields(StudioPlugin plugin, Optional<String> payload) {
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
    return configurations
        .find(pluginId)
        .orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private StudioPlugin requirePlugin(String pluginId) {
    return registry.find(pluginId).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private static PluginAuthHandler requireAuthHandler(StudioPlugin plugin) {
    return plugin
        .authHandler()
        .orElseThrow(
            () ->
                new AiValidationException(
                    RESOURCE, "plugin does not provide interactive authentication"));
  }

  private static PluginAuthSpec.DeepLink requireDeepLink(StudioPlugin plugin) {
    if (plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink deepLink) {
      return deepLink;
    }
    throw new AiValidationException(RESOURCE, "plugin does not provide interactive authentication");
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
