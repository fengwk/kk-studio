package fun.fengwk.kkstudio.platform.plugin.service;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.common.schema.SchemaJsonCodec;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialProjection;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginSecretProjectionDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Plugin 管理面用例：列出/读取安全投影，保存配置与秘密补丁，以及 {@code DEEP_LINK} 认证的 prepare / complete / disconnect。
 *
 * <p>它只路由到已安装 Plugin：未安装的 {@code pluginId} 返回 not found，绝不加载或合成 Plugin。认证协议是封闭的，请求与响应都只有共享 DTO
 * 声明的字段；回调地址只在本次调用内短暂存在，既不落库也不进日志、响应或异常消息。
 *
 * <p>交互式认证是「一个 Plugin 至多一个 pending attempt」的一次性协议：prepare 先向 Plugin 取登录地址，再写入只含 nonce 摘要与期望坐标的
 * attempt；complete 必须携带同一 attemptId，服务端以单条条件删除原子消费它，并在坐标仍与当前 manifest 一致、材料 region 与 prepare 选定
 * region 逐字相等时才落库。因此重放、过期与配置/认证变化后的迟到回调都无法换取凭据。
 *
 * <p>配置与秘密的写入没有旁路：保存、登录完成与断连都经 {@link PluginConfigurationCommitService} 的唯一 CAS 提交入口，返回的是权威坐标与当前
 * applied 视图，绝不把保存当作发布。
 */
@Slf4j
public final class PluginManagementService {

  private static final String RESOURCE = "plugin";
  private static final SchemaJsonCodec SCHEMA_CODEC = new SchemaJsonCodec();

  private final StudioPluginRegistry registry;
  private final Supplier<PluginCatalogSnapshot> appliedSnapshot;
  private final PluginConfigurationRepository configurationRepository;
  private final PluginCredentialStore credentialStore;
  private final PluginConfigurationCommitService commitService;
  private final PluginAuthAttemptRepository authAttempts;
  private final Duration authAttemptTtl;
  private final Clock clock;

  public PluginManagementService(
      StudioPluginRegistry registry,
      Supplier<PluginCatalogSnapshot> appliedSnapshot,
      PluginConfigurationRepository configurationRepository,
      PluginCredentialStore credentialStore,
      PluginConfigurationCommitService commitService,
      PluginAuthAttemptRepository authAttempts,
      Duration authAttemptTtl,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.appliedSnapshot = Objects.requireNonNull(appliedSnapshot, "appliedSnapshot");
    this.configurationRepository =
        Objects.requireNonNull(configurationRepository, "configurationRepository");
    this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore");
    this.commitService = Objects.requireNonNull(commitService, "commitService");
    this.authAttempts = Objects.requireNonNull(authAttempts, "authAttempts");
    this.authAttemptTtl = Objects.requireNonNull(authAttemptTtl, "authAttemptTtl");
    if (authAttemptTtl.isZero() || authAttemptTtl.isNegative()) {
      throw new IllegalArgumentException("authAttemptTtl must be a positive duration");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 已安装 Plugin 的安全投影，顺序跟随启动期冻结的安装目录。 */
  public List<PluginDTO> listPlugins() {
    return registry.plugins().stream().map(this::describe).toList();
  }

  /** 单个已安装 Plugin 的安全投影；未安装 id 返回 not found。 */
  public PluginDTO getPlugin(String pluginId) {
    return describe(requirePlugin(pluginId));
  }

  /**
   * 保存一次普通配置与秘密补丁。
   *
   * <p>期望坐标不可省略，冲突返回 version conflict；秘密补丁只作用于 schema 声明的秘密字段，未知字段、非法操作与遮罩占位符都 fail closed。
   */
  public PluginDTO updateConfiguration(
      String pluginId,
      long expectedEpoch,
      long expectedRevision,
      String valuesJson,
      Map<String, PluginSecretPatch> secretPatch) {
    StudioPlugin plugin = requirePlugin(pluginId);
    commitService.configure(pluginId, expectedEpoch, expectedRevision, valuesJson, secretPatch);
    return describe(plugin);
  }

  /**
   * 选择固定 region，向 Plugin 取该 region 的官方登录地址，并写入本次唯一的 pending attempt。
   *
   * <p>顺序固定：先校验 region 候选、拿到登录地址，再写 attempt。因此 Plugin 拒绝 region 或没有给出地址时不会留下悬空 attempt；写入本身是单条
   * upsert，同一个 Plugin 的旧 pending attempt 立即失效。返回值只有 attemptId 与登录地址，nonce 原文绝不返回或落库。
   */
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
    PluginCatalogManifest.Entry entry = requireEntry(requireCatalog(), pluginId);
    String authAttemptId = PluginAuthNonce.issue();
    Instant now = clock.instant();
    authAttempts.replace(
        new PluginAuthAttemptRow(
            pluginId,
            PluginAuthNonce.hash(authAttemptId),
            entry.authGeneration(),
            entry.contextFingerprint(),
            entry.configRevision(),
            region,
            now.plus(authAttemptTtl)));
    PluginAuthPrepareDTO response = new PluginAuthPrepareDTO();
    response.setAuthAttemptId(authAttemptId);
    response.setLoginUrl(loginUrl);
    return response;
  }

  /**
   * 消费 pending attempt、校验回调、把 opaque 凭据材料经唯一 CAS 提交入口加密落库，并返回新的安全投影。
   *
   * <p>封闭协议、attempt 身份与期望坐标在调用 handler 之前先判定，绝不先做远端动作再校验：attempt 必须存在、未过期、摘要命中且当前 manifest 的
   * 代际/上下文/配置修订仍与 prepare 时逐字段一致，否则迟到回调直接以冲突失败。材料必须与 prepare 选定的 region 逐字相等，不因为仍在 region
   * 候选集合内就被接受。凭据落库仍走 epoch CAS，因此消费与落库之间发生的任何配置变化都会让落库失败而不是覆盖新设置。Plugin 抛出的任何异常都收敛为固定校验失败： 回调原文与
   * nonce 绝不进入异常消息、cause 链或日志。
   */
  public PluginDTO completeAuth(String pluginId, String authAttemptId, String callbackUrl) {
    StudioPlugin plugin = requirePlugin(pluginId);
    requireDeepLink(plugin);
    PluginAuthHandler handler = requireAuthHandler(plugin);
    if (authAttemptId == null || authAttemptId.isBlank()) {
      throw new AiValidationException(RESOURCE, "authAttemptId must not be blank");
    }
    if (callbackUrl == null || callbackUrl.isBlank()) {
      throw new AiValidationException(RESOURCE, "callbackUrl must not be blank");
    }
    AttemptConsumption consumption = consumeAttempt(pluginId, authAttemptId);
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
        || !consumption.attempt().region().equals(material.region())) {
      throw new AiValidationException(
          RESOURCE, "plugin returned a credential outside the prepared region");
    }
    commitService.completeAuth(pluginId, consumption.epoch(), material);
    return describe(plugin);
  }

  /**
   * 原子单次消费 attempt：消费语句在同一次判定里要求摘要命中、未过期、全局 epoch 未前进且当前 manifest 坐标仍与 attempt 逐字段相等。
   *
   * <p>读一次 attempt 只为区分「未知/过期/已被取代」与「坐标已漂移」两种失败（后者是 409），绝不作为放行依据：真正的门禁是那条带 epoch 与 manifest
   * 坐标条件的条件删除，因此 revoke/配置变更提交之后绝不可能再换来一次性回调许可。
   */
  private AttemptConsumption consumeAttempt(String pluginId, String authAttemptId) {
    PluginCatalogRecord catalog = requireCatalog();
    PluginCatalogManifest.Entry entry = requireEntry(catalog, pluginId);
    Instant now = clock.instant();
    String nonceHash = PluginAuthNonce.hash(authAttemptId);
    PluginAuthAttemptRow pending =
        authAttempts
            .find(pluginId)
            .filter(attempt -> attempt.nonceHash().equals(nonceHash))
            .orElseThrow(PluginManagementService::unknownAttempt);
    if (!pending.authGeneration().equals(entry.authGeneration())
        || !pending.contextFingerprint().equals(entry.contextFingerprint())
        || pending.configRevision() != entry.configRevision()) {
      throw new AiVersionConflictException(
          RESOURCE, "revision=" + pending.configRevision(), "revision=" + entry.configRevision());
    }
    PluginAuthAttemptRow consumed =
        authAttempts
            .consume(pluginId, nonceHash, now, catalog.epoch())
            .orElseThrow(PluginManagementService::unknownAttempt);
    return new AttemptConsumption(consumed, catalog.epoch());
  }

  private static AiValidationException unknownAttempt() {
    return new AiValidationException(RESOURCE, "auth attempt is unknown, expired or already used");
  }

  /** 断连：删除凭据行并推进 epoch；未连接时同样成功返回（幂等）。 */
  public void disconnect(String pluginId) {
    requirePlugin(pluginId);
    commitService.disconnect(pluginId, requireCatalog().epoch());
  }

  private StudioPlugin requirePlugin(String pluginId) {
    return registry.find(pluginId).orElseThrow(() -> new AiResourceNotFoundException(RESOURCE));
  }

  private PluginCatalogRecord requireCatalog() {
    PluginCatalogRecord catalog = configurationRepository.readCatalog();
    if (catalog == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return catalog;
  }

  /** 该 Plugin 在权威 manifest 中的冻结坐标；未安装或不属于已发布目录都返回 not found。 */
  private static PluginCatalogManifest.Entry requireEntry(
      PluginCatalogRecord catalog, String pluginId) {
    PluginCatalogManifest.Entry entry = catalog.manifest().plugins().get(pluginId);
    if (entry == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return entry;
  }

  private static PluginAuthHandler requireAuthHandler(StudioPlugin plugin) {
    return plugin
        .authHandler()
        .orElseThrow(
            () ->
                new AiValidationException(
                    RESOURCE, "plugin does not provide interactive authentication"));
  }

  /** 只有 {@link PluginAuthSpec.DeepLink} 承接 region 候选与交互式登录；None/StaticKey 不假冒。 */
  private static PluginAuthSpec.DeepLink requireDeepLink(StudioPlugin plugin) {
    if (plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink deepLink) {
      return deepLink;
    }
    throw new AiValidationException(RESOURCE, "plugin does not provide interactive authentication");
  }

  /** 安全投影：安装身份、认证状态、desired 配置与 desired/applied 坐标；秘密只以说明与布尔出现。 */
  private PluginDTO describe(StudioPlugin plugin) {
    PluginDescriptor descriptor = plugin.descriptor();
    PluginCatalogRecord catalog = requireCatalog();
    PluginDTO dto = new PluginDTO();
    dto.setPluginId(descriptor.pluginId());
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    PluginAuthKindDTO authKind = authKindOf(plugin.authenticationSpec());
    dto.setAuthKind(authKind);
    if (authKind != null) {
      // 只有真正声明认证能力的 Plugin 才探测凭据表：没有认证就没有凭据状态，也绝不因为缺主密钥而谎报 KEY_UNAVAILABLE。
      PluginCredentialProjection projection = credentialStore.projection(descriptor.pluginId());
      dto.setStatus(projection.status().name());
      dto.setRegion(projection.region());
      dto.setExpiresAt(projection.expiresAt());
      dto.setNextRefreshAt(projection.nextRefreshAt());
      dto.setLastRefreshedAt(projection.lastRefreshedAt());
      dto.setLastRefreshError(projection.lastRefreshError());
    }
    dto.setConfiguration(configurationOf(plugin, catalog));
    dto.setDesiredEpoch(Long.toString(catalog.epoch()));
    dto.setAppliedEpoch(Long.toString(requireAppliedSnapshot().epoch()));
    PluginCatalogManifest.Entry desiredEntry =
        catalog.manifest().plugins().get(descriptor.pluginId());
    dto.setDesiredRevision(
        desiredEntry == null ? null : Long.toString(desiredEntry.configRevision()));
    dto.setAppliedRevision(appliedRevisionOf(descriptor.pluginId()));
    dto.setApplicationError(
        catalog.epoch() == requireAppliedSnapshot().epoch()
            ? null
            : PluginConfigurationCommitService.PENDING_APPLICATION_ERROR);
    return dto;
  }

  /**
   * desired 配置投影：普通值、修订与已录入秘密字段都来自权威 catalog 中该 Plugin
   * 当前条目的历史行，因此「刚保存但本节点尚未应用」时前端读到的仍是本次保存的值，不会把编辑回退成旧值。
   *
   * <p>本节点实际应用到的坐标与能力由 {@code appliedRevision}/{@code appliedEpoch} 单独表达，两者不混。
   */
  private PluginConfigurationDTO configurationOf(StudioPlugin plugin, PluginCatalogRecord catalog) {
    String pluginId = plugin.descriptor().pluginId();
    PluginConfigurationDTO configuration = new PluginConfigurationDTO();
    PluginConfigSchema schema = plugin.configurationSchema();
    configuration.setValuesSchema(SCHEMA_CODEC.encode(schema.valuesSchema()));
    configuration.setDefaultsJson(schema.defaultsJson());
    Set<String> configuredFields = Set.of();
    PluginCatalogManifest.Entry desired = catalog.manifest().plugins().get(pluginId);
    if (desired != null) {
      PluginConfigurationRow history =
          PluginConfigurationHistory.require(configurationRepository, pluginId, desired);
      configuration.setRevision(Long.toString(history.revision()));
      configuration.setValuesJson(history.valuesJson());
      configuredFields = Set.copyOf(history.configuredSecretFields());
    }
    Map<String, PluginSecretProjectionDTO> secrets = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : schema.secrets().entrySet()) {
      PluginSecretProjectionDTO secret = new PluginSecretProjectionDTO();
      secret.setDescription(entry.getValue());
      secret.setConfigured(configuredFields.contains(entry.getKey()));
      secrets.put(entry.getKey(), secret);
    }
    configuration.setSecrets(secrets);
    return configuration;
  }

  /** 本节点当前 applied 快照：只在真正需要投影真实应用状态时才向容器索取，装配期不解析。 */
  private PluginCatalogSnapshot requireAppliedSnapshot() {
    return Objects.requireNonNull(appliedSnapshot.get(), "applied plugin catalog snapshot");
  }

  private Optional<PluginCatalogSnapshot.ConfiguredPlugin> appliedPlugin(String pluginId) {
    return requireAppliedSnapshot().configuredPlugins().stream()
        .filter(configured -> configured.descriptor().pluginId().equals(pluginId))
        .findFirst();
  }

  private String appliedRevisionOf(String pluginId) {
    return appliedPlugin(pluginId)
        .map(configured -> Long.toString(configured.config().revision()))
        .orElse(null);
  }

  /** 一次 complete 已消费的 attempt 与消费时的权威全局 epoch：落库必须仍匹配该 epoch。 */
  private record AttemptConsumption(PluginAuthAttemptRow attempt, long epoch) {}

  /** 认证交互投影：DeepLink 给 region 候选，StaticKey 给唯一秘密字段名，None 为 null。 */
  private static PluginAuthKindDTO authKindOf(PluginAuthSpec authSpec) {
    return switch (authSpec) {
      case PluginAuthSpec.DeepLink deepLink -> new PluginAuthKindDTO.DeepLink(deepLink.regions());
      case PluginAuthSpec.StaticKey staticKey -> new PluginAuthKindDTO.StaticKey(
          staticKey.secretField());
      case PluginAuthSpec.None ignored -> null;
    };
  }
}
