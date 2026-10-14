package fun.fengwk.kkstudio.platform.plugin.catalog;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 启动期一次 configure 出的 Plugin 两域快照，并携带该快照的冻结 epoch、owner map 与身份指纹。
 *
 * <p>构建时逐个校验安装定义与配置的一致性、按归一化后的配置装配实例，并把插件贡献与容器真正内置的贡献一起冻结成完整的 {@link HarnessCatalog} 与 {@link
 * CanvasFunctionCatalog}：两域目录的唯一性与依赖校验都在返回前完成，任何半成品都不会外泄。冻结后即从实际冻结目录计算全局 catalog 指纹与 每个插件的 owner
 * 能力指纹，owner map 只记录冻结实例产出的 ContributorId 与 Canvas function 名，不再次 configure/contribute。
 *
 * <p>快照是插件实例的唯一所有者：配置失败、读取贡献失败或目录冲突时回收全部已创建实例；{@link #close()} 幂等、按逆序释放， 单个 {@link
 * RuntimeException} 不阻其它实例回收，进程级 {@link Error} 继续独立回收后仍向上传播。
 */
@Slf4j
public final class PluginCatalogSnapshot implements AutoCloseable {

  private final long epoch;
  private final List<ConfiguredPlugin> configuredPlugins;
  private final HarnessCatalog harnessCatalog;
  private final CanvasFunctionCatalog canvasFunctionCatalog;
  private final Map<String, PluginOwnership> pluginOwnership;
  private final String catalogFingerprint;
  private final Map<String, String> capabilityFingerprints;

  private final Object lifecycleLock = new Object();
  private boolean closed;

  private PluginCatalogSnapshot(
      long epoch,
      List<ConfiguredPlugin> configuredPlugins,
      HarnessCatalog harnessCatalog,
      CanvasFunctionCatalog canvasFunctionCatalog,
      Map<String, PluginOwnership> pluginOwnership,
      String catalogFingerprint,
      Map<String, String> capabilityFingerprints) {
    this.epoch = epoch;
    this.configuredPlugins = configuredPlugins;
    this.harnessCatalog = harnessCatalog;
    this.canvasFunctionCatalog = canvasFunctionCatalog;
    this.pluginOwnership = pluginOwnership;
    this.catalogFingerprint = catalogFingerprint;
    this.capabilityFingerprints = capabilityFingerprints;
  }

  /**
   * 用每个已安装 Plugin 的配置装配一次快照，并绑定该快照的 epoch。
   *
   * <p>{@code configurations} 必须精确覆盖安装集合：缺少任一已安装 id 或带有未知 id 都直接失败，绝不静默回退缺失配置；每个配置的 revision 都必须不晚于
   * {@code epoch}；{@code contexts} 为每个安装 id 提供 scoped {@link PluginContext}；{@code builtins} 与
   * {@code builtinAdapters} 是容器真正内置的贡献，不被伪装成外部插件。
   *
   * @param epoch 该快照对应的权威全局 epoch，必须非负
   * @throws IllegalStateException 配置覆盖不全、安装定义不一致或两域目录构建失败时（只带 stage 与 pluginId，不含底层 cause）
   */
  public static PluginCatalogSnapshot build(
      long epoch,
      StudioPluginRegistry registry,
      Map<String, PluginConfig> configurations,
      Function<String, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(configurations, "configurations");
    Objects.requireNonNull(contexts, "contexts");
    Objects.requireNonNull(builtins, "builtins");
    Objects.requireNonNull(builtinAdapters, "builtinAdapters");
    if (epoch < 0) {
      throw new IllegalArgumentException("epoch must not be negative");
    }

    requireConfigurationCoverage(registry, configurations);

    List<ConfiguredPlugin> configured = new ArrayList<>();
    Map<String, PluginOwnership> ownership = new LinkedHashMap<>();
    List<HarnessContributor> contributors = new ArrayList<>(builtins);
    List<CanvasFunctionAdapter> adapters = new ArrayList<>(builtinAdapters);
    String stage = "configuration";
    String activeId = null;
    try {
      for (StudioPlugin plugin : registry.plugins()) {
        PluginDescriptor descriptor = plugin.descriptor();
        activeId = descriptor.pluginId();

        stage = "configuration-consistency";
        PluginAuthSpec authSpec = plugin.authenticationSpec();
        authSpec.requireConsistentWith(plugin.authHandler());
        plugin.configurationSchema().requireDeclaredSecrets(authSpec);

        stage = "configuration";
        PluginConfig config = configurations.get(activeId);
        if (config == null) {
          throw new IllegalStateException("plugin configuration is missing");
        }
        if (config.revision() > epoch) {
          throw new IllegalStateException(
              "plugin configuration revision must not exceed the snapshot epoch");
        }
        PluginConfig normalized =
            new PluginConfig(
                config.revision(), plugin.configurationSchema().resolveValues(config.valuesJson()));

        stage = "configure";
        PluginContext context = contexts.apply(activeId);
        if (context == null) {
          throw new IllegalStateException("plugin context is missing");
        }
        PluginInstance instance = plugin.configure(normalized, context);
        if (instance == null) {
          throw new IllegalStateException("plugin configure returned no instance");
        }
        // 立即纳入回收列表：随后读取贡献列表失败时实例也必须被回收，绝不泄漏。
        configured.add(new ConfiguredPlugin(descriptor, normalized, instance));

        stage = "collect-contributions";
        List<HarnessContributor> pluginContributors =
            Objects.requireNonNull(instance.harnessContributors(), "harnessContributors");
        List<CanvasFunctionAdapter> pluginAdapters =
            Objects.requireNonNull(instance.canvasAdapters(), "canvasAdapters");
        ownership.put(activeId, ownershipOf(pluginContributors, pluginAdapters));
        contributors.addAll(pluginContributors);
        adapters.addAll(pluginAdapters);
      }

      stage = "catalog";
      activeId = null;
      HarnessCatalog harnessCatalog = HarnessCatalog.from(contributors);
      CanvasFunctionCatalog canvasFunctionCatalog = CanvasFunctionCatalog.from(adapters);

      stage = "fingerprint";
      String catalogFingerprint =
          PluginCatalogFingerprint.catalog(harnessCatalog, canvasFunctionCatalog);
      Map<String, String> capabilityFingerprints = new LinkedHashMap<>();
      for (Map.Entry<String, PluginOwnership> entry : ownership.entrySet()) {
        PluginOwnership own = entry.getValue();
        capabilityFingerprints.put(
            entry.getKey(),
            PluginCatalogFingerprint.capability(
                harnessCatalog, canvasFunctionCatalog, own.contributorIds(), own.functionNames()));
      }

      // 两域目录的唯一性与依赖校验全部通过、身份指纹全部冻结后才返回快照，半成品不外泄。
      return new PluginCatalogSnapshot(
          epoch,
          List.copyOf(configured),
          harnessCatalog,
          canvasFunctionCatalog,
          Collections.unmodifiableMap(ownership),
          catalogFingerprint,
          Collections.unmodifiableMap(capabilityFingerprints));
    } catch (RuntimeException error) {
      log.warn(
          "Plugin catalog snapshot build failed at stage {} for plugin {}: {}",
          stage,
          activeId,
          error.getClass().getSimpleName());
      Error cleanupError = closeAll(configured);
      if (cleanupError != null) {
        throw cleanupError;
      }
      throw buildFailure(stage, activeId);
    } catch (Error error) {
      // 进程级致命错误：已创建实例一个都不能泄漏；回收即使再致命也不得替代原始 Error。
      log.error(
          "Plugin catalog snapshot build failed fatally at stage {} for plugin {}: {}",
          stage,
          activeId,
          error.getClass().getName());
      closeAll(configured);
      throw error;
    }
  }

  /** 按安装 id 顺序冻结的已配置 Plugin；每个实例由本快照唯一拥有。 */
  public List<ConfiguredPlugin> configuredPlugins() {
    return configuredPlugins;
  }

  /** 聚合插件贡献与容器内置贡献后的完整 Harness 目录。 */
  public HarnessCatalog harnessCatalog() {
    return harnessCatalog;
  }

  /** 聚合插件 adapter 与容器内置 adapter 后的完整 Canvas Function 目录。 */
  public CanvasFunctionCatalog canvasFunctionCatalog() {
    return canvasFunctionCatalog;
  }

  /** 该快照对应的权威全局 epoch。 */
  public long epoch() {
    return epoch;
  }

  /** 每个已安装 pluginId 在其冻结实例中产出的 ContributorId 与 Canvas function 名（不可变）。 */
  public Map<String, PluginOwnership> pluginOwnership() {
    return pluginOwnership;
  }

  /** 完整冻结两域目录的全局指纹（含内置与插件贡献）。 */
  public String catalogFingerprint() {
    return catalogFingerprint;
  }

  /** 每个已安装 pluginId 的能力指纹（按 owner map 筛选其冻结两域贡献）。 */
  public Map<String, String> capabilityFingerprints() {
    return capabilityFingerprints;
  }

  /** 幂等关闭：按逆序释放全部插件实例，单个 {@link RuntimeException} 只记录类型，不阻塞其它回收。 */
  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    Error fatal = closeAll(configuredPlugins);
    if (fatal != null) {
      throw fatal;
    }
  }

  private static void requireConfigurationCoverage(
      StudioPluginRegistry registry, Map<String, PluginConfig> configurations) {
    for (String pluginId : registry.pluginIds()) {
      if (!configurations.containsKey(pluginId)) {
        throw buildFailure("configuration", pluginId);
      }
    }
    for (String pluginId : configurations.keySet()) {
      if (registry.find(pluginId).isEmpty()) {
        throw buildFailure("configuration", pluginId);
      }
    }
  }

  /** 只从冻结实例产出的声明读取 owner 身份，不再次 configure/contribute。 */
  private static PluginOwnership ownershipOf(
      List<HarnessContributor> contributors, List<CanvasFunctionAdapter> adapters) {
    Set<ContributorId> contributorIds = new LinkedHashSet<>();
    for (HarnessContributor contributor : contributors) {
      contributorIds.add(
          Objects.requireNonNull(contributor, "harnessContributor").descriptor().id());
    }
    Set<String> functionNames = new LinkedHashSet<>();
    for (CanvasFunctionAdapter adapter : adapters) {
      for (CanvasFunctionDefinition function :
          Objects.requireNonNull(adapter, "canvasAdapter").functions()) {
        functionNames.add(Objects.requireNonNull(function, "canvasFunction").name());
      }
    }
    return new PluginOwnership(contributorIds, functionNames);
  }

  /**
   * 逆序回收实例，绝不在此抛出：单个 {@link RuntimeException} 只记录类型，继续回收其余实例；{@link Error} 也继续回收但保留首个
   * 并作为返回值交给调用方，不被次要异常替代或吞掉。
   *
   * @return 首个回收期 {@link Error}；没有则为 {@code null}
   */
  private static Error closeAll(List<ConfiguredPlugin> plugins) {
    Error fatal = null;
    for (int index = plugins.size() - 1; index >= 0; index--) {
      ConfiguredPlugin configured = plugins.get(index);
      String pluginId = configured.descriptor().pluginId();
      try {
        configured.instance().close();
      } catch (RuntimeException error) {
        log.warn("Plugin instance close failed for {}: {}", pluginId, error.getClass().getName());
      } catch (Error error) {
        log.error(
            "Plugin instance close failed fatally for {}: {}",
            pluginId,
            error.getClass().getName());
        if (fatal == null) {
          fatal = error;
        }
      }
    }
    return fatal;
  }

  private static IllegalStateException buildFailure(String stage, String pluginId) {
    String suffix = pluginId == null ? "" : " pluginId=" + pluginId;
    return new IllegalStateException(
        "Plugin catalog snapshot build failed: stage=" + stage + suffix);
  }

  /** 一次配置产出的、由本快照拥有的运行期实例。 */
  public record ConfiguredPlugin(
      PluginDescriptor descriptor, PluginConfig config, PluginInstance instance) {

    public ConfiguredPlugin {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(config, "config");
      Objects.requireNonNull(instance, "instance");
    }
  }

  /** 单个 Plugin 在其冻结实例中产出的 owner 身份：ContributorId 与 Canvas function 名，均不可变。 */
  public record PluginOwnership(Set<ContributorId> contributorIds, Set<String> functionNames) {

    public PluginOwnership {
      contributorIds = Set.copyOf(Objects.requireNonNull(contributorIds, "contributorIds"));
      functionNames = Set.copyOf(Objects.requireNonNull(functionNames, "functionNames"));
    }
  }
}
