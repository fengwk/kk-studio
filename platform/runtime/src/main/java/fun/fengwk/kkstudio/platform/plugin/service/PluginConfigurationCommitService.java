package fun.fengwk.kkstudio.platform.plugin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.json.BoundedJsonWriter;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginAuthContextFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.FixedPluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import javax.crypto.SecretKey;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Plugin 配置与秘密的唯一提交入口：普通 revision、秘密替换/清除与完整 manifest 在同一个全局 epoch CAS 事务内一起提交。
 *
 * <p>没有任何绕过 catalog 授权围栏的写路径：登录完成、断连与配置保存都经过本类；凭据行只以「匹配基线认证代际、上下文指纹与 version」的条件语句写入或删除，
 * 因此旧引用绝不可能无条件 upsert 复活一行已被替换或解除的凭据。
 *
 * <p>每次提交都先构建完整 desired 两域候选：目标插件用「本次 patch 的有效秘密视图」装配私有候选上下文（不提前改活动凭据、不发任何外部请求），其余插件沿用权威 scoped
 * 访问；候选冻结后即校验两域目录冲突并计算 catalog 与能力指纹。随后在同一事务内先 CAS 全局 epoch 与插入历史（锁 {@code
 * catalog_state}），再按基线条件写凭据行（锁 credential 行）。任一步失败都整体回滚，候选实例在所有分支上都被关闭。
 *
 * <p>刷新仍只操作 credential 行。CAS 未命中不写历史、不写秘密、不发通知，所有非变更 manifest entry 与基线逐字段一致。
 */
@Slf4j
public final class PluginConfigurationCommitService {

  /** 保存成功但本节点尚未应用时对外如实说明的固定去敏文案。 */
  public static final String PENDING_APPLICATION_ERROR =
      "configuration is saved but this node has not applied it yet";

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final StudioPluginRegistry registry;
  private final PluginConfigurationRepository configurationRepository;
  private final PluginCredentialRepository credentialRepository;
  private final PluginCredentialCodec codec;
  private final PluginCredentialKeyLoader keyLoader;
  private final PluginContextFactory contextFactory;
  private final Supplier<List<HarnessContributor>> builtins;
  private final Supplier<List<CanvasFunctionAdapter>> builtinAdapters;
  private final TransactionTemplate commitTemplate;
  private final Clock clock;
  private final Runnable credentialChanged;

  public PluginConfigurationCommitService(
      StudioPluginRegistry registry,
      PluginConfigurationRepository configurationRepository,
      PluginCredentialRepository credentialRepository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader,
      PluginContextFactory contextFactory,
      Supplier<List<HarnessContributor>> builtins,
      Supplier<List<CanvasFunctionAdapter>> builtinAdapters,
      PlatformTransactionManager transactionManager,
      Clock clock,
      Runnable credentialChanged) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.configurationRepository =
        Objects.requireNonNull(configurationRepository, "configurationRepository");
    this.credentialRepository =
        Objects.requireNonNull(credentialRepository, "credentialRepository");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.keyLoader = Objects.requireNonNull(keyLoader, "keyLoader");
    this.contextFactory = Objects.requireNonNull(contextFactory, "contextFactory");
    this.builtins = Objects.requireNonNull(builtins, "builtins");
    this.builtinAdapters = Objects.requireNonNull(builtinAdapters, "builtinAdapters");
    this.commitTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    this.clock = Objects.requireNonNull(clock, "clock");
    this.credentialChanged = Objects.requireNonNull(credentialChanged, "credentialChanged");
  }

  /**
   * 保存一次普通配置与秘密补丁。
   *
   * @param expectedEpoch 期望的全局 catalog epoch；不匹配即 {@link AiVersionConflictException}
   * @param expectedRevision 期望的该插件配置修订；不匹配即 {@link AiVersionConflictException}
   * @param valuesJson 严格 JSON object 普通值原文（秘密不得出现在这里）
   * @param secretPatch 秘密字段补丁；省略的声明字段按 KEEP 处理
   * @return 提交后的权威 catalog 记录
   */
  public PluginCatalogRecord configure(
      String pluginId,
      long expectedEpoch,
      long expectedRevision,
      String valuesJson,
      Map<String, PluginSecretPatch> secretPatch) {
    StudioPlugin plugin = requirePlugin(pluginId);
    PluginAuthSpec authSpec = plugin.authenticationSpec();
    Map<String, PluginSecretPatch> patch = requirePatch(plugin, secretPatch);
    String resolvedValues;
    try {
      resolvedValues = plugin.configurationSchema().resolveValues(valuesJson);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException("plugin", "valuesJson must match the plugin schema");
    }
    PluginCatalogRecord catalog = requireCatalog();
    PluginCatalogManifest.Entry entry =
        requireEntry(catalog, pluginId, expectedEpoch, expectedRevision);
    Optional<PluginCredentialRow> baseline = credentialRepository.find(pluginId);

    String schemaFingerprint =
        PluginCatalogFingerprint.schema(plugin.configurationSchema(), authSpec);
    String contextFingerprint = contextFingerprintOf(plugin, schemaFingerprint, resolvedValues);
    return commit(
        plugin,
        catalog,
        entry,
        baseline,
        resolvedValues,
        schemaFingerprint,
        contextFingerprint,
        patch,
        expectedEpoch);
  }

  /**
   * 登录完成：以交互材料替换凭据行并推进 epoch，配置值保持不变。
   *
   * @param expectedEpoch 期望的全局 catalog epoch；不匹配即 {@link AiVersionConflictException}
   */
  public PluginCatalogRecord completeAuth(
      String pluginId, long expectedEpoch, PluginCredentialMaterial material) {
    Objects.requireNonNull(material, "material");
    StudioPlugin plugin = requirePlugin(pluginId);
    if (!(plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink deepLink)) {
      throw new AiValidationException(
          "plugin", "plugin does not provide interactive authentication");
    }
    if (!material.interactive() || !deepLink.acceptsRegion(material.region())) {
      throw new AiValidationException(
          "plugin", "plugin returned a credential outside its declared regions");
    }
    // 本地时限门禁（原写入路径的约束不因入口变化而消失）：已过期的材料绝不落库，避免写入一行立刻就不可用的凭据。
    Instant now = clock.instant();
    if (!material.expiresAt().isAfter(now)) {
      throw new AiValidationException("plugin", "plugin returned an already expired credential");
    }
    if (material.nextRefreshAt().isBefore(now)) {
      throw new AiValidationException("plugin", "plugin returned a refresh instant in the past");
    }
    PluginCatalogRecord catalog = requireCatalog();
    PluginCatalogManifest.Entry entry = requireEntry(catalog, pluginId, expectedEpoch, null);
    Optional<PluginCredentialRow> baseline = credentialRepository.find(pluginId);
    String resolvedValues = requireHistoryValues(pluginId, entry);
    String schemaFingerprint =
        PluginCatalogFingerprint.schema(plugin.configurationSchema(), plugin.authenticationSpec());
    String contextFingerprint = contextFingerprintOf(plugin, schemaFingerprint, resolvedValues);
    // 交互材料是不透明载荷：原样落库（插件可能带 refresh token 等未在 schema 声明的字段），
    // 只有「已录入的声明秘密字段名」按声明过滤，绝不截断载荷本身。
    return executeCommit(
        plugin,
        catalog,
        entry,
        baseline,
        resolvedValues,
        schemaFingerprint,
        contextFingerprint,
        UUID.randomUUID(),
        declaredFields(plugin, material.payloadJson()),
        material,
        CredentialAction.WRITE,
        expectedEpoch);
  }

  /**
   * 断连：删除凭据行并推进 epoch；未连接时同样成功（幂等），配置值保持不变。
   *
   * @param expectedEpoch 期望的全局 catalog epoch；不匹配即 {@link AiVersionConflictException}
   */
  public PluginCatalogRecord disconnect(String pluginId, long expectedEpoch) {
    StudioPlugin plugin = requirePlugin(pluginId);
    PluginCatalogRecord catalog = requireCatalog();
    PluginCatalogManifest.Entry entry = requireEntry(catalog, pluginId, expectedEpoch, null);
    Optional<PluginCredentialRow> baseline = credentialRepository.find(pluginId);
    String resolvedValues = requireHistoryValues(pluginId, entry);
    String schemaFingerprint =
        PluginCatalogFingerprint.schema(plugin.configurationSchema(), plugin.authenticationSpec());
    String contextFingerprint = contextFingerprintOf(plugin, schemaFingerprint, resolvedValues);
    return executeCommit(
        plugin,
        catalog,
        entry,
        baseline,
        resolvedValues,
        schemaFingerprint,
        contextFingerprint,
        UUID.randomUUID(),
        List.of(),
        null,
        baseline.isPresent() ? CredentialAction.REMOVE : CredentialAction.NONE,
        expectedEpoch);
  }

  /**
   * 按 patch 与上下文变化决定凭据动作，并保持「纯 KEEP 普通配置不写凭据、不破坏 lease/version」。
   *
   * <p>主密钥是惰性的：没有任何秘密操作且认证上下文未变化时是纯普通配置提交；CLEAR 掉全部已录入字段时是条件删除；两条路径都不读取主密钥也不解密任何密文，
   * 因此缺主密钥不会阻止用户撤销秘密或只改普通配置。只有确实要读出并重写秘密的分支才 {@link #requireKey()}，此时缺失主密钥必须 fail closed。
   */
  private PluginCatalogRecord commit(
      StudioPlugin plugin,
      PluginCatalogRecord catalog,
      PluginCatalogManifest.Entry entry,
      Optional<PluginCredentialRow> baseline,
      String resolvedValues,
      String schemaFingerprint,
      String contextFingerprint,
      Map<String, PluginSecretPatch> patch,
      long expectedEpoch) {
    boolean touched =
        patch.values().stream()
            .anyMatch(operation -> operation.operation() != PluginSecretPatch.Operation.KEEP);
    boolean contextChanged = !contextFingerprint.equals(entry.contextFingerprint());
    boolean changeRequested = touched || contextChanged;
    UUID generation = changeRequested ? UUID.randomUUID() : entry.authGeneration();
    // 已录入字段的事实源是当前 revision 的历史行：无论有没有凭据行都必须存在且与 entry 逐字段一致，否则 fail closed，
    // 既不悄悄当成「未录入」，也不改写一个身份已经漂移的目录。
    List<String> baselineFields = configuredFieldsOf(plugin, entry);
    if (!changeRequested) {
      return executeCommit(
          plugin,
          catalog,
          entry,
          baseline,
          resolvedValues,
          schemaFingerprint,
          contextFingerprint,
          generation,
          baselineFields,
          null,
          CredentialAction.NONE,
          expectedEpoch);
    }
    Set<String> configured = new LinkedHashSet<>(baselineFields);
    applyFieldsPatch(configured, patch);
    if (configured.isEmpty()) {
      return executeCommit(
          plugin,
          catalog,
          entry,
          baseline,
          resolvedValues,
          schemaFingerprint,
          contextFingerprint,
          generation,
          List.of(),
          null,
          baseline.isPresent() ? CredentialAction.REMOVE : CredentialAction.NONE,
          expectedEpoch);
    }
    PluginCredentialRow existing = baseline.orElse(null);
    // 只有「已录入且本次未被 SET 替换、也未被 CLEAR 清除」的字段才需要旧值：全部被替换或清除时既不读主密钥也不解密旧密文。
    ObjectNode payload =
        needsBaselinePayload(baselineFields, patch) && existing != null
            ? declaredPayload(plugin, requireKey(), existing)
            : new ObjectNode(NODES);
    applyPatch(payload, patch);
    // 多字段组合、多字节与转义展开都在完整分配前受同一份 64 KiB 预算约束。
    String payloadJson = boundedPayload(payload);
    List<String> configuredFields = declaredFields(plugin, payloadJson);
    PluginCredentialMaterial candidate =
        new PluginCredentialMaterial(
            existing == null ? null : existing.region(),
            existing == null ? null : existing.expiresAt(),
            existing == null ? null : existing.nextRefreshAt(),
            payloadJson);
    return executeCommit(
        plugin,
        catalog,
        entry,
        baseline,
        resolvedValues,
        schemaFingerprint,
        contextFingerprint,
        generation,
        configuredFields,
        candidate,
        CredentialAction.WRITE,
        expectedEpoch);
  }

  /** 构建 desired 候选、在 CAS 事务内提交普通 revision + 秘密替换/清除 + 完整 manifest，并如实返回写后记录。 */
  private PluginCatalogRecord executeCommit(
      StudioPlugin plugin,
      PluginCatalogRecord catalog,
      PluginCatalogManifest.Entry entry,
      Optional<PluginCredentialRow> baseline,
      String resolvedValues,
      String schemaFingerprint,
      String contextFingerprint,
      UUID generation,
      List<String> configuredSecretFields,
      PluginCredentialMaterial candidate,
      CredentialAction action,
      long expectedEpoch) {
    String pluginId = plugin.descriptor().pluginId();
    long newEpoch = Math.addExact(expectedEpoch, 1);
    PluginCredentialReference candidateReference =
        new PluginCredentialReference(pluginId, generation, contextFingerprint);
    PluginCredentialSnapshot candidateSnapshot = null;
    final byte[] encryptedPayload;
    if (candidate == null) {
      encryptedPayload = null;
    } else {
      // 候选用密文与凭据行必须逐字节相同，因此只加密一次，两边共享同一份输出。
      encryptedPayload =
          codec.encrypt(
              requireKey(),
              pluginId,
              generation,
              contextFingerprint,
              candidate.region(),
              candidate.payloadJson());
      candidateSnapshot =
          new PluginCredentialSnapshot(
              pluginId,
              generation,
              contextFingerprint,
              candidate.region(),
              candidate.expiresAt(),
              0L,
              encryptedPayload,
              candidate.payloadJson());
    }
    PluginCatalogSnapshot snapshot = null;
    Error primaryError = null;
    try {
      Map<String, PluginConfigurationState> states =
          candidateStates(catalog, pluginId, resolvedValues, candidateReference, newEpoch);
      snapshot =
          PluginCatalogSnapshot.build(
              newEpoch,
              registry,
              states,
              candidateContexts(pluginId, candidateReference, action, candidateSnapshot),
              builtins.get(),
              builtinAdapters.get());
      PluginCatalogManifest manifest =
          candidateManifest(
              catalog,
              pluginId,
              entry,
              schemaFingerprint,
              contextFingerprint,
              generation,
              newEpoch,
              snapshot);
      PluginConfigurationRow changed =
          new PluginConfigurationRow(
              pluginId,
              newEpoch,
              plugin.descriptor().version(),
              schemaFingerprint,
              manifest.plugins().get(pluginId).capabilityFingerprint(),
              generation,
              contextFingerprint,
              configuredSecretFields,
              resolvedValues);
      Boolean committed =
          commitTemplate.execute(
              status ->
                  commitWithinTransaction(
                      pluginId,
                      baseline,
                      expectedEpoch,
                      changed,
                      manifest,
                      candidate,
                      encryptedPayload,
                      action,
                      generation,
                      contextFingerprint));
      if (!Boolean.TRUE.equals(committed)) {
        throw new AiVersionConflictException(
            "plugin", "epoch=" + expectedEpoch, "epoch=" + catalog.epoch());
      }
      return new PluginCatalogRecord(newEpoch, manifest);
    } catch (Error error) {
      primaryError = error;
      throw error;
    } finally {
      if (snapshot != null) {
        try {
          snapshot.close();
        } catch (Error cleanup) {
          if (primaryError == null) {
            throw cleanup;
          }
        }
      }
    }
  }

  /** 事务内：先 CAS 全局 epoch 与历史（锁 catalog_state），再按基线条件写凭据行。 */
  private boolean commitWithinTransaction(
      String pluginId,
      Optional<PluginCredentialRow> baseline,
      long expectedEpoch,
      PluginConfigurationRow changed,
      PluginCatalogManifest manifest,
      PluginCredentialMaterial candidate,
      byte[] encryptedPayload,
      CredentialAction action,
      UUID generation,
      String contextFingerprint) {
    if (!configurationRepository.commit(expectedEpoch, List.of(changed), manifest)) {
      return false;
    }
    if (action == CredentialAction.NONE) {
      return true;
    }
    PluginCredentialRow existing = baseline.orElse(null);
    if (action == CredentialAction.REMOVE) {
      if (!credentialRepository.delete(
          pluginId, existing.authGeneration(), existing.contextFingerprint(), existing.version())) {
        throw credentialChangedConcurrently(pluginId);
      }
      registerCredentialChanged();
      return true;
    }
    Instant now = clock.instant();
    PluginCredentialRow row =
        new PluginCredentialRow(
            pluginId,
            generation,
            contextFingerprint,
            encryptedPayload,
            candidate.region(),
            candidate.expiresAt(),
            candidate.nextRefreshAt(),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    boolean written =
        existing == null
            ? credentialRepository.insert(row)
            : credentialRepository.replace(
                row, existing.authGeneration(), existing.contextFingerprint(), existing.version());
    if (!written) {
      throw credentialChangedConcurrently(pluginId);
    }
    registerCredentialChanged();
    return true;
  }

  /** 凭据行的条件写（代际/上下文/version CAS）未命中：对外是版本冲突，绝不无依据重试或覆盖。 */
  private static AiVersionConflictException credentialChangedConcurrently(String pluginId) {
    return new AiVersionConflictException(
        "plugin", "credential of " + pluginId, "credential changed concurrently");
  }

  /** 提交成功后唤醒刷新调度：有活跃事务时排到提交后，避免通知早于新凭据可见。 */
  private void registerCredentialChanged() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              credentialChanged.run();
            }
          });
    } else {
      credentialChanged.run();
    }
  }

  private Map<String, PluginConfigurationState> candidateStates(
      PluginCatalogRecord catalog,
      String pluginId,
      String resolvedValues,
      PluginCredentialReference candidateReference,
      long newEpoch) {
    Map<String, PluginConfigurationState> states = new LinkedHashMap<>();
    for (Map.Entry<String, PluginCatalogManifest.Entry> manifestEntry :
        catalog.manifest().plugins().entrySet()) {
      String id = manifestEntry.getKey();
      PluginCatalogManifest.Entry entry = manifestEntry.getValue();
      if (id.equals(pluginId)) {
        states.put(
            id,
            new PluginConfigurationState(
                new PluginConfig(newEpoch, resolvedValues), candidateReference));
        continue;
      }
      String values = requireHistoryValues(id, entry);
      states.put(
          id,
          new PluginConfigurationState(
              new PluginConfig(entry.configRevision(), values),
              new PluginCredentialReference(
                  id, entry.authGeneration(), entry.contextFingerprint())));
    }
    return states;
  }

  /**
   * 候选上下文：目标 Plugins 一律使用本次提交的「私有候选视图」，其余插件沿用权威 scoped 访问。
   *
   * <p>候选视图有三种真实形态：新密文（SET/登录完成）、本次私有的「无凭据」（CLEAR 或本就未连接）与权威访问（纯 KEEP 且凭据行不变）。删除与未连接两种候选都绝不回读权威
   * store：前者在事务提交前旧行仍然存在，回读会把即将被撤销的秘密交给候选实例。所有候选都只影响本次构建，不进入活动快照。
   */
  private BiFunction<String, PluginCredentialReference, PluginContext> candidateContexts(
      String pluginId,
      PluginCredentialReference candidateReference,
      CredentialAction action,
      PluginCredentialSnapshot candidateSnapshot) {
    if (action == CredentialAction.NONE) {
      return contextFactory::create;
    }
    return (id, reference) -> {
      if (id.equals(pluginId)) {
        PluginContext base = contextFactory.create(id, reference);
        return new PluginContext(
            new FixedPluginCredentialAccess(candidateReference, candidateSnapshot),
            base.resourceGateway());
      }
      return contextFactory.create(id, reference);
    };
  }

  private PluginCatalogManifest candidateManifest(
      PluginCatalogRecord catalog,
      String pluginId,
      PluginCatalogManifest.Entry entry,
      String schemaFingerprint,
      String contextFingerprint,
      UUID generation,
      long newEpoch,
      PluginCatalogSnapshot snapshot) {
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>();
    for (Map.Entry<String, PluginCatalogManifest.Entry> manifestEntry :
        catalog.manifest().plugins().entrySet()) {
      if (manifestEntry.getKey().equals(pluginId)) {
        plugins.put(
            pluginId,
            new PluginCatalogManifest.Entry(
                newEpoch,
                entry.pluginCodeVersion(),
                schemaFingerprint,
                snapshot.capabilityFingerprints().get(pluginId),
                generation,
                contextFingerprint));
      } else {
        plugins.put(manifestEntry.getKey(), manifestEntry.getValue());
      }
    }
    return new PluginCatalogManifest(plugins, snapshot.catalogFingerprint());
  }

  /** 把秘密补丁应用到候选载荷；字段合法性、操作合法性与值合法性都由调用方先校验。 */
  private static void applyPatch(ObjectNode payload, Map<String, PluginSecretPatch> patch) {
    for (Map.Entry<String, PluginSecretPatch> operation : patch.entrySet()) {
      switch (operation.getValue().operation()) {
        case KEEP -> {
          // 省略与显式 KEEP 都是「保留」。
        }
        case SET -> payload.put(operation.getKey(), operation.getValue().value());
        case CLEAR -> payload.remove(operation.getKey());
      }
    }
  }

  /** 把补丁的字段增删应用到「已录入秘密字段名」集合：SET 加入、CLEAR 移除、KEEP 不变。 */
  private static void applyFieldsPatch(
      Set<String> configured, Map<String, PluginSecretPatch> patch) {
    for (Map.Entry<String, PluginSecretPatch> operation : patch.entrySet()) {
      switch (operation.getValue().operation()) {
        case KEEP -> {
          // 保留已录入状态。
        }
        case SET -> configured.add(operation.getKey());
        case CLEAR -> configured.remove(operation.getKey());
      }
    }
  }

  /** 有界写出候选载荷：多字段组合、多字节与转义展开都在完整分配前受同一份 64 KiB 预算约束。 */
  private static String boundedPayload(ObjectNode payload) {
    String payloadJson =
        BoundedJsonWriter.write(payload, PluginCredentialMaterial.MAX_PAYLOAD_UTF8_BYTES);
    if (payloadJson == null) {
      throw new AiValidationException(
          "plugin", "secret patch payload must not exceed the credential budget");
    }
    return payloadJson;
  }

  /** 补丁后是否仍有「已录入但未被 SET 替换、也未被 CLEAR 清除」的字段需要旧值。 */
  private static boolean needsBaselinePayload(
      List<String> baselineFields, Map<String, PluginSecretPatch> patch) {
    for (String field : baselineFields) {
      PluginSecretPatch operation = patch.get(field);
      if (operation == null || operation.operation() == PluginSecretPatch.Operation.KEEP) {
        return true;
      }
    }
    return false;
  }

  /** 该 Plugin 当前 revision 已录入的声明秘密字段名；历史必须存在且与 entry 逐字段一致，否则 fail closed。 */
  private List<String> configuredFieldsOf(StudioPlugin plugin, PluginCatalogManifest.Entry entry) {
    return PluginConfigurationHistory.require(
            configurationRepository, plugin.descriptor().pluginId(), entry)
        .configuredSecretFields();
  }

  /** 基线载荷解密后限制到该 schema 声明的秘密字段，避免把不透明内容带入静态补丁视图。 */
  private ObjectNode declaredPayload(StudioPlugin plugin, SecretKey key, PluginCredentialRow row) {
    String payload =
        codec.decrypt(
            key,
            row.pluginId(),
            row.authGeneration(),
            row.contextFingerprint(),
            row.region(),
            row.encryptedPayload());
    return declaredPayloadOf(plugin, payload);
  }

  /** 把一段载荷限制到该 schema 声明的秘密字段。 */
  private static ObjectNode declaredPayloadOf(StudioPlugin plugin, String payloadJson) {
    JsonNode node = JsonValues.readTree(payloadJson);
    ObjectNode declared = new ObjectNode(NODES);
    if (!node.isObject()) {
      return declared;
    }
    for (String field : plugin.configurationSchema().secrets().keySet()) {
      JsonNode value = node.get(field);
      if (value != null) {
        declared.set(field, value);
      }
    }
    return declared;
  }

  /** 已录入的声明秘密字段名；只有字段名，绝无值。 */
  private static List<String> declaredFields(StudioPlugin plugin, String payloadJson) {
    JsonNode node = JsonValues.readTree(payloadJson);
    List<String> fields = new ArrayList<>();
    if (!node.isObject()) {
      return fields;
    }
    for (String field : plugin.configurationSchema().secrets().keySet()) {
      if (node.has(field)) {
        fields.add(field);
      }
    }
    return fields;
  }

  private static String contextFingerprintOf(
      StudioPlugin plugin, String schemaFingerprint, String resolvedValues) {
    return PluginAuthContextFingerprint.derive(
        plugin.descriptor().pluginId(),
        plugin.descriptor().version(),
        schemaFingerprint,
        plugin.authenticationSpec(),
        JsonValues.readTree(resolvedValues));
  }

  private Map<String, PluginSecretPatch> requirePatch(
      StudioPlugin plugin, Map<String, PluginSecretPatch> secretPatch) {
    Map<String, PluginSecretPatch> patch = new LinkedHashMap<>();
    if (secretPatch == null) {
      return patch;
    }
    boolean interactive = plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink;
    for (Map.Entry<String, PluginSecretPatch> entry : secretPatch.entrySet()) {
      String field = entry.getKey();
      if (!plugin.configurationSchema().secrets().containsKey(field)) {
        // 固定文案：字段名来自请求体，属于用户输入，绝不回显。
        throw new AiValidationException("plugin", "secret patch must name a declared secret field");
      }
      PluginSecretPatch operation = Objects.requireNonNull(entry.getValue(), "secretPatch value");
      if (interactive && operation.operation() != PluginSecretPatch.Operation.KEEP) {
        throw new AiValidationException(
            "plugin", "interactive credentials are replaced by login completion");
      }
      patch.put(field, operation);
    }
    return patch;
  }

  private StudioPlugin requirePlugin(String pluginId) {
    return registry.find(pluginId).orElseThrow(() -> new AiResourceNotFoundException("plugin"));
  }

  private SecretKey requireKey() {
    return keyLoader
        .load()
        .orElseThrow(
            () -> new PluginKeyUnavailableException("plugin credential key is unavailable"));
  }

  private PluginCatalogRecord requireCatalog() {
    PluginCatalogRecord catalog = configurationRepository.readCatalog();
    if (catalog == null) {
      throw new PluginKeyUnavailableException("plugin catalog is unavailable");
    }
    return catalog;
  }

  private PluginCatalogManifest.Entry requireEntry(
      PluginCatalogRecord catalog, String pluginId, long expectedEpoch, Long expectedRevision) {
    if (catalog.epoch() != expectedEpoch) {
      throw new AiVersionConflictException(
          "plugin", "epoch=" + expectedEpoch, "epoch=" + catalog.epoch());
    }
    PluginCatalogManifest.Entry entry = catalog.manifest().plugins().get(pluginId);
    if (entry == null) {
      throw new AiResourceNotFoundException("plugin");
    }
    if (expectedRevision != null && entry.configRevision() != expectedRevision) {
      throw new AiVersionConflictException(
          "plugin", "revision=" + expectedRevision, "revision=" + entry.configRevision());
    }
    return entry;
  }

  private String requireHistoryValues(String pluginId, PluginCatalogManifest.Entry entry) {
    return PluginConfigurationHistory.requireValues(configurationRepository, pluginId, entry);
  }

  /** 本次提交对凭据行的动作。 */
  private enum CredentialAction {
    NONE,
    WRITE,
    REMOVE
  }
}
