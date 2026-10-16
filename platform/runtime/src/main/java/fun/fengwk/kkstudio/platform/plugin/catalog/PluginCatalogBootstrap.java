package fun.fengwk.kkstudio.platform.plugin.catalog;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Plugin catalog 的启动初始化与按持久版本恢复。
 *
 * <p>它读取权威 catalog 单行：行缺失直接失败，绝不默认兜底。epoch=0 时以 epoch=1 与全部安装定义的默认普通值构建完整两域候选，计算实际 manifest
 * 与历史并在一次短事务内 CAS {@code expected=0}；命中即返回该候选，CAS 败方关闭自己的候选、一次回读赢家并走 manifest 恢复（显式首次
 * 初始化竞争，不循环重试）。epoch>0 时安装集合必须精确等于 manifest，每个 entry 的 revision 必须不晚于 epoch，history row 的
 * id/revision/code/schema/capability 必须与 entry 逐字段相等，code/schema 还必须等于当前 JAR；随后能力与 catalog 指纹必须与
 * manifest 一致。任一不符都明确失败，绝不偷偷采用新 defaults 或转换。
 *
 * <p>候选快照建成后的所有失败分支（manifest 构造、CAS 提交、恢复期校验）都独立释放候选的全部实例：受控诊断只带 stage 与类名，回收期 {@link
 * RuntimeException} 只记录类型；回收期 {@link Error} 仅在原始失败为 {@link RuntimeException} 时上抛，原始失败本身是 {@link
 * Error} 时它原样传播、绝不被回收错误替代。成功快照唯一拥有其实例，按 Spring 原协议关闭。
 */
public final class PluginCatalogBootstrap {

  /** V1 未初始化形态的唯一 epoch。 */
  static final long UNINITIALIZED_EPOCH = 0L;

  /** 首次初始化写入的目标 epoch。 */
  static final long INITIAL_EPOCH = 1L;

  private final StudioPluginRegistry registry;

  private final PluginConfigurationRepository repository;

  private final TransactionTemplate commitTemplate;

  public PluginCatalogBootstrap(
      StudioPluginRegistry registry,
      PluginConfigurationRepository repository,
      PlatformTransactionManager transactionManager) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.commitTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  /**
   * 初始化或恢复唯一快照。
   *
   * @param contexts 为每个安装 id 提供 scoped {@link PluginContext}
   * @param builtins 容器真正内置的 Harness 贡献
   * @param builtinAdapters 容器真正内置的 Canvas adapter
   * @throws IllegalStateException 权威行缺失、身份漂移或指纹不符时（只带受控 stage，不含底层 cause）
   */
  public PluginCatalogSnapshot bootstrap(
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    Objects.requireNonNull(contexts, "contexts");
    Objects.requireNonNull(builtins, "builtins");
    Objects.requireNonNull(builtinAdapters, "builtinAdapters");

    PluginCatalogRecord catalog = repository.readCatalog();
    if (catalog == null) {
      throw failure("catalog-missing", null, null);
    }
    if (catalog.epoch() == UNINITIALIZED_EPOCH) {
      return initialize(contexts, builtins, builtinAdapters);
    }
    return restore(catalog, contexts, builtins, builtinAdapters);
  }

  private PluginCatalogSnapshot initialize(
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    PreparedInitial prepared =
        buildInitial(defaultConfigurations(), Map.of(), contexts, builtins, builtinAdapters);
    PluginCatalogSnapshot candidate = prepared.snapshot();

    boolean committed;
    try {
      committed =
          Boolean.TRUE.equals(
              commitTemplate.execute(
                  status ->
                      repository.commit(
                          UNINITIALIZED_EPOCH, prepared.configurationRows(), prepared.manifest())));
    } catch (RuntimeException | Error error) {
      throw releaseConvergingFailure("initialize-commit", null, error, candidate);
    }
    if (committed) {
      return candidate;
    }

    // CAS 败方：关闭自己的候选，一次回读赢家并走 manifest 恢复，不循环重试。
    candidate.close();
    PluginCatalogRecord winner = repository.readCatalog();
    if (winner == null || winner.epoch() == UNINITIALIZED_EPOCH) {
      throw failure("initialize-race", null, null);
    }
    return restore(winner, contexts, builtins, builtinAdapters);
  }

  /**
   * 在任意目标写事务之外纯构造一次 epoch 1 的初始候选，绝不读权威 catalog、绝不提交任何写入。
   *
   * <p>输入必须精确覆盖安装集合：{@code valuesJsonByPluginId} 与 {@code configuredSecretFieldsByPluginId} 的 key
   * 集都必须恰好等于当前安装目录，缺项或未知 id 都直接失败，绝不用当前 defaults 偷偷补齐。每个普通值先按该 Plugin 的 schema 归一化；配置的 secret field
   * 名只能是该 schema 声明的秘密字段，稳定排序并拒绝重复，且本方法从不接收任何秘密值。
   *
   * <p>返回的 {@link PreparedInitial} 与 {@link #bootstrap} 首次初始化复用同一套 Schema
   * 解析、schema/context/capability/catalog 指纹与 manifest 构建算法，因此逐字段一致。结果只持有候选快照、manifest
   * 与完整配置历史行；{@link PreparedInitial#snapshot()} 的资源所有权交给调用方， 结果不自动提交，也不拥有任何独立 registry 或服务。
   *
   * @param valuesJsonByPluginId 每个已安装 id 的初始普通值 JSON，key 集必须精确等于安装集合
   * @param configuredSecretFieldsByPluginId 每个已安装 id 的「已录入」秘密字段名，key 集必须精确等于安装集合；只接受字段名，绝不携带秘密值
   * @throws IllegalStateException 安装集合不符、普通值非法或 secret field 名非法时（只带受控 stage，不含底层 cause，也绝不回显值或秘密）
   */
  public PreparedInitial prepareInitial(
      Map<String, String> valuesJsonByPluginId,
      Map<String, List<String>> configuredSecretFieldsByPluginId,
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    Objects.requireNonNull(valuesJsonByPluginId, "valuesJsonByPluginId");
    Objects.requireNonNull(configuredSecretFieldsByPluginId, "configuredSecretFieldsByPluginId");
    Objects.requireNonNull(contexts, "contexts");
    Objects.requireNonNull(builtins, "builtins");
    Objects.requireNonNull(builtinAdapters, "builtinAdapters");

    Set<String> installed = new LinkedHashSet<>(registry.pluginIds());
    if (!installed.equals(valuesJsonByPluginId.keySet())) {
      throw failure("prepare-values-set", null, null);
    }
    if (!installed.equals(configuredSecretFieldsByPluginId.keySet())) {
      throw failure("prepare-secret-fields-set", null, null);
    }

    Map<String, PluginConfigurationState> configurations = new LinkedHashMap<>();
    Map<String, List<String>> configuredSecretFields = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      String resolved = resolveInitialValues(plugin, valuesJsonByPluginId.get(pluginId));
      configuredSecretFields.put(
          pluginId, declaredSecretFields(plugin, configuredSecretFieldsByPluginId.get(pluginId)));
      configurations.put(
          pluginId,
          new PluginConfigurationState(
              new PluginConfig(INITIAL_EPOCH, resolved),
              new PluginCredentialReference(
                  pluginId,
                  UUID.randomUUID(),
                  contextFingerprintFor(plugin, schemaFingerprintFor(plugin), resolved))));
    }
    return buildInitial(
        configurations, configuredSecretFields, contexts, builtins, builtinAdapters);
  }

  /**
   * 以 epoch 1 组装一次完整初始候选（快照 + manifest + 完整配置历史行）。
   *
   * <p>普通 {@link #initialize} 与离线 {@link #prepareInitial} 共用这一条构建路径：候选在任何目标写事务之外建成；manifest
   * 或行构造失败时通过 {@link #releaseConvergingFailure} 独立释放候选的全部实例，绝不外泄半成品。
   */
  private PreparedInitial buildInitial(
      Map<String, PluginConfigurationState> configurations,
      Map<String, List<String>> configuredSecretFields,
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    PluginCatalogSnapshot candidate =
        PluginCatalogSnapshot.build(
            INITIAL_EPOCH, registry, configurations, contexts, builtins, builtinAdapters);
    try {
      PluginCatalogManifest manifest = manifestFor(candidate);
      return new PreparedInitial(
          candidate, manifest, changedRowsFor(candidate, manifest, configuredSecretFields));
    } catch (RuntimeException | Error error) {
      throw releaseConvergingFailure("initialize-manifest", null, error, candidate);
    }
  }

  /** 归一化一个已安装 Plugin 的初始普通值：非法输入收敛为只带 stage 与 pluginId 的受控诊断，绝不回显原文。 */
  private static String resolveInitialValues(StudioPlugin plugin, String valuesJson) {
    try {
      return plugin.configurationSchema().resolveValues(valuesJson);
    } catch (RuntimeException error) {
      throw failure("prepare-values", plugin.descriptor().pluginId(), null);
    }
  }

  /** 校验「已录入」秘密字段名：只能为本 Plugin schema 声明的秘密字段，稳定排序、拒绝重复，绝不接收秘密值。 */
  private static List<String> declaredSecretFields(StudioPlugin plugin, List<String> fields) {
    if (fields == null) {
      throw failure("prepare-secret-fields", plugin.descriptor().pluginId(), null);
    }
    Set<String> declared = plugin.configurationSchema().secrets().keySet();
    Set<String> unique = new LinkedHashSet<>();
    for (String field : fields) {
      if (field == null || !declared.contains(field) || !unique.add(field)) {
        throw failure("prepare-secret-fields", plugin.descriptor().pluginId(), null);
      }
    }
    List<String> sorted = new ArrayList<>(unique);
    Collections.sort(sorted);
    return List.copyOf(sorted);
  }

  /**
   * 按已初始化 record 严格恢复完整快照；启动初始化与运行期对账共用这一条路径。
   *
   * <p>安装集合必须精确等于 manifest，每个 entry 的 revision 不得晚于 record 的 epoch，history row 的
   * id/revision/code/schema/capability/generation/context 必须与 entry 逐字段相等，code/schema 还必须等于当前 JAR，
   * 最后能力与 catalog 指纹必须与 manifest 一致。本入口只读且可复用：它不写库、不采用当前 defaults、也不把未初始化形态偷偷初始化， {@code
   * catalog.epoch() == 0} 直接失败。
   *
   * @param contexts 为每个安装 id 提供 scoped {@link PluginContext}
   * @param builtins 容器真正内置的 Harness 贡献
   * @param builtinAdapters 容器真正内置的 Canvas adapter
   * @throws IllegalStateException record 未初始化、身份漂移或指纹不符时（只带受控 stage，不含底层 cause）
   */
  public PluginCatalogSnapshot restore(
      PluginCatalogRecord catalog,
      BiFunction<String, PluginCredentialReference, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(contexts, "contexts");
    Objects.requireNonNull(builtins, "builtins");
    Objects.requireNonNull(builtinAdapters, "builtinAdapters");
    if (catalog.epoch() == UNINITIALIZED_EPOCH) {
      throw failure("restore-uninitialized", null, null);
    }
    PluginCatalogManifest manifest = catalog.manifest();
    if (!new LinkedHashSet<>(registry.pluginIds()).equals(manifest.plugins().keySet())) {
      throw failure("restore-install-set", null, null);
    }

    Map<String, PluginConfigurationState> configurations = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      PluginCatalogManifest.Entry entry = manifest.plugins().get(pluginId);
      Optional<PluginConfigurationRow> found;
      try {
        found = repository.readConfiguration(pluginId, entry.configRevision());
      } catch (RuntimeException error) {
        throw failure("restore-read", pluginId, error);
      }
      PluginConfigurationRow row =
          found.orElseThrow(() -> failure("restore-missing-history", pluginId, null));
      requireRowMatchesEntry(pluginId, entry, row);
      if (!row.codeVersion().equals(plugin.descriptor().version())) {
        throw failure("restore-code-version", pluginId, null);
      }
      if (!row.schemaFingerprint().equals(schemaFingerprintFor(plugin))) {
        throw failure("restore-schema-fingerprint", pluginId, null);
      }
      // 恢复必须重新派生认证上下文指纹并与持久值逐字段核对：配置或定义漂移不偷偷采用。
      String derivedContext =
          contextFingerprintFor(plugin, row.schemaFingerprint(), row.valuesJson());
      if (!derivedContext.equals(row.contextFingerprint())) {
        throw failure("restore-context-fingerprint", pluginId, null);
      }
      configurations.put(
          pluginId,
          new PluginConfigurationState(
              new PluginConfig(entry.configRevision(), row.valuesJson()),
              new PluginCredentialReference(
                  pluginId, row.authGeneration(), row.contextFingerprint())));
    }

    PluginCatalogSnapshot candidate =
        PluginCatalogSnapshot.build(
            catalog.epoch(), registry, configurations, contexts, builtins, builtinAdapters);
    try {
      if (!candidate.catalogFingerprint().equals(manifest.catalogFingerprint())) {
        throw failure("restore-catalog-fingerprint", null, null);
      }
      for (Map.Entry<String, PluginCatalogManifest.Entry> entry : manifest.plugins().entrySet()) {
        if (!entry
            .getValue()
            .capabilityFingerprint()
            .equals(candidate.capabilityFingerprints().get(entry.getKey()))) {
          throw failure("restore-capability-fingerprint", entry.getKey(), null);
        }
      }
    } catch (RuntimeException | Error error) {
      throw releasePreservingFailure(candidate, error);
    }
    return candidate;
  }

  /**
   * history row 必须与 manifest entry 在 id/revision/code/schema/capability/generation/context 上逐字段相等。
   */
  private static void requireRowMatchesEntry(
      String pluginId, PluginCatalogManifest.Entry entry, PluginConfigurationRow row) {
    if (!row.pluginId().equals(pluginId)
        || row.revision() != entry.configRevision()
        || !row.codeVersion().equals(entry.pluginCodeVersion())
        || !row.schemaFingerprint().equals(entry.schemaFingerprint())
        || !row.capabilityFingerprint().equals(entry.capabilityFingerprint())
        || !row.authGeneration().equals(entry.authGeneration())
        || !row.contextFingerprint().equals(entry.contextFingerprint())) {
      throw failure("restore-history-identity", pluginId, null);
    }
  }

  /** 首次初始化配置身份：每个初始插件都用 schema 默认值，并生成一个全新认证代际（即便当前没有凭据，也绝不用 0 UUID 或 rowVersion 模拟身份）。 */
  private Map<String, PluginConfigurationState> defaultConfigurations() {
    Map<String, PluginConfigurationState> configurations = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      PluginConfig config =
          new PluginConfig(INITIAL_EPOCH, plugin.configurationSchema().defaultsJson());
      String contextFingerprint =
          contextFingerprintFor(
              plugin,
              PluginCatalogFingerprint.schema(
                  plugin.configurationSchema(), plugin.authenticationSpec()),
              config.valuesJson());
      configurations.put(
          pluginId,
          new PluginConfigurationState(
              config,
              new PluginCredentialReference(pluginId, UUID.randomUUID(), contextFingerprint)));
    }
    return configurations;
  }

  private PluginCatalogManifest manifestFor(PluginCatalogSnapshot snapshot) {
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>();
    for (PluginCatalogSnapshot.ConfiguredPlugin configured : snapshot.configuredPlugins()) {
      String pluginId = configured.descriptor().pluginId();
      StudioPlugin plugin =
          registry.find(pluginId).orElseThrow(() -> failure("manifest-plugin", pluginId, null));
      plugins.put(
          pluginId,
          new PluginCatalogManifest.Entry(
              configured.config().revision(),
              configured.descriptor().version(),
              schemaFingerprintFor(plugin),
              snapshot.capabilityFingerprints().get(pluginId),
              configured.credentialReference().authGeneration(),
              configured.credentialReference().contextFingerprint()));
    }
    return new PluginCatalogManifest(plugins, snapshot.catalogFingerprint());
  }

  private List<PluginConfigurationRow> changedRowsFor(
      PluginCatalogSnapshot snapshot,
      PluginCatalogManifest manifest,
      Map<String, List<String>> configuredSecretFields) {
    List<PluginConfigurationRow> rows = new ArrayList<>();
    for (PluginCatalogSnapshot.ConfiguredPlugin configured : snapshot.configuredPlugins()) {
      String pluginId = configured.descriptor().pluginId();
      PluginCatalogManifest.Entry entry = manifest.plugins().get(pluginId);
      rows.add(
          new PluginConfigurationRow(
              pluginId,
              configured.config().revision(),
              configured.descriptor().version(),
              entry.schemaFingerprint(),
              entry.capabilityFingerprint(),
              configured.credentialReference().authGeneration(),
              configured.credentialReference().contextFingerprint(),
              configuredSecretFields.getOrDefault(pluginId, List.of()),
              configured.config().valuesJson()));
    }
    return rows;
  }

  private static String schemaFingerprintFor(StudioPlugin plugin) {
    return PluginCatalogFingerprint.schema(
        plugin.configurationSchema(), plugin.authenticationSpec());
  }

  /** 从已归一化普通值派生认证上下文指纹：与持久值同源，绝不使用未归一化原文。 */
  private static String contextFingerprintFor(
      StudioPlugin plugin, String schemaFingerprint, String valuesJson) {
    String normalized = plugin.configurationSchema().resolveValues(valuesJson);
    return PluginAuthContextFingerprint.derive(
        plugin.descriptor().pluginId(),
        plugin.descriptor().version(),
        schemaFingerprint,
        plugin.authenticationSpec(),
        JsonValues.readTree(normalized));
  }

  /**
   * 释放候选实例并原样保留受控诊断：原始 {@link Error} 原样上抛；回收期 {@link Error} 只在原始失败为 {@link RuntimeException} 时取代它。
   */
  private static RuntimeException releasePreservingFailure(
      PluginCatalogSnapshot candidate, Throwable primary) {
    try {
      candidate.close();
    } catch (Error cleanup) {
      // Snapshot 已独立记录并回收全部实例；原始致命错误不可被次要回收错误替代。
      if (!(primary instanceof Error)) {
        throw cleanup;
      }
    }
    if (primary instanceof Error fatal) {
      throw fatal;
    }
    return (RuntimeException) primary;
  }

  /** 释放候选实例并把非受控失败收敛为受控 stage 诊断（只带 stage 与类名）；原始 {@link Error} 仍原样上抛。 */
  private static RuntimeException releaseConvergingFailure(
      String stage, String pluginId, Throwable primary, PluginCatalogSnapshot candidate) {
    return failure(stage, pluginId, releasePreservingFailure(candidate, primary));
  }

  /** 受控失败诊断：只带 stage，必要时带 pluginId 与次要异常的类名，绝不附带底层 cause 或原始细节。 */
  private static IllegalStateException failure(String stage, String pluginId, Throwable error) {
    StringBuilder message =
        new StringBuilder("Plugin catalog bootstrap failed: stage=").append(stage);
    if (pluginId != null) {
      message.append(" pluginId=").append(pluginId);
    }
    if (error != null) {
      message.append(" cause=").append(error.getClass().getSimpleName());
    }
    return new IllegalStateException(message.toString());
  }

  /**
   * 一次 epoch 1 初始候选的纯构造结果：候选快照、冻结 manifest 与完整配置历史行。
   *
   * <p>它不自动提交，也不拥有任何独立 registry 或服务；{@link #snapshot()} 的资源所有权明确交给调用方，调用方必须在失败或结束时关闭该快照。
   */
  public record PreparedInitial(
      PluginCatalogSnapshot snapshot,
      PluginCatalogManifest manifest,
      List<PluginConfigurationRow> configurationRows) {

    public PreparedInitial {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(manifest, "manifest");
      configurationRows =
          List.copyOf(Objects.requireNonNull(configurationRows, "configurationRows"));
    }
  }
}
