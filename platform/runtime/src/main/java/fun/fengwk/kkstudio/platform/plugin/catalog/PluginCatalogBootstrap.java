package fun.fengwk.kkstudio.platform.plugin.catalog;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
      Function<String, PluginContext> contexts,
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
      Function<String, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    PluginCatalogSnapshot candidate =
        PluginCatalogSnapshot.build(
            INITIAL_EPOCH, registry, defaultConfigurations(), contexts, builtins, builtinAdapters);

    PluginCatalogManifest manifest;
    List<PluginConfigurationRow> changed;
    try {
      manifest = manifestFor(candidate);
      changed = changedRowsFor(candidate, manifest);
    } catch (RuntimeException | Error error) {
      throw releaseConvergingFailure("initialize-manifest", null, error, candidate);
    }

    boolean committed;
    try {
      committed =
          Boolean.TRUE.equals(
              commitTemplate.execute(
                  status -> repository.commit(UNINITIALIZED_EPOCH, changed, manifest)));
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

  private PluginCatalogSnapshot restore(
      PluginCatalogRecord catalog,
      Function<String, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    PluginCatalogManifest manifest = catalog.manifest();
    if (!new LinkedHashSet<>(registry.pluginIds()).equals(manifest.plugins().keySet())) {
      throw failure("restore-install-set", null, null);
    }

    Map<String, PluginConfig> configurations = new LinkedHashMap<>();
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
      configurations.put(pluginId, new PluginConfig(entry.configRevision(), row.valuesJson()));
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

  /** history row 必须与 manifest entry 在 id/revision/code/schema/capability 上逐字段相等。 */
  private static void requireRowMatchesEntry(
      String pluginId, PluginCatalogManifest.Entry entry, PluginConfigurationRow row) {
    if (!row.pluginId().equals(pluginId)
        || row.revision() != entry.configRevision()
        || !row.codeVersion().equals(entry.pluginCodeVersion())
        || !row.schemaFingerprint().equals(entry.schemaFingerprint())
        || !row.capabilityFingerprint().equals(entry.capabilityFingerprint())) {
      throw failure("restore-history-identity", pluginId, null);
    }
  }

  private Map<String, PluginConfig> defaultConfigurations() {
    Map<String, PluginConfig> configurations = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      configurations.put(
          plugin.descriptor().pluginId(),
          new PluginConfig(INITIAL_EPOCH, plugin.configurationSchema().defaultsJson()));
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
              snapshot.capabilityFingerprints().get(pluginId)));
    }
    return new PluginCatalogManifest(plugins, snapshot.catalogFingerprint());
  }

  private List<PluginConfigurationRow> changedRowsFor(
      PluginCatalogSnapshot snapshot, PluginCatalogManifest manifest) {
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
              configured.config().valuesJson()));
    }
    return rows;
  }

  private static String schemaFingerprintFor(StudioPlugin plugin) {
    return PluginCatalogFingerprint.schema(
        plugin.configurationSchema(), plugin.authenticationSpec());
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
}
