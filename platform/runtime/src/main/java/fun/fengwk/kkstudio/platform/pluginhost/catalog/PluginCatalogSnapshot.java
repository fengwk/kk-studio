package fun.fengwk.kkstudio.platform.pluginhost.catalog;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 一次 configure 出的 Plugin 快照：每个已安装 Plugin 的实例、与内置贡献合并后的 {@link
 * HarnessCatalog}，以及由注入的产品目录投影冻结的产品目录视图。
 *
 * <p>平台只拥有它自己解释的 Harness 目录；产品目录（例如 Canvas 的函数目录）由对应产品模块提供的 {@link Function} 构建并冻结，平台随后只用 {@link
 * #productCatalog(Class)} 按类型读取，不 import 也不解释产品类型。因此每个产品只有一份已构建好的目录视图，租用快照的执行路径不会重复读取 adapter 定义。
 *
 * <p>快照是插件实例的唯一所有者：构建失败时回收已创建的实例，{@link #close()} 幂等且按逆序释放；单个实例关闭失败不阻断其它实例回收。
 */
@Slf4j
public final class PluginCatalogSnapshot implements AutoCloseable {

  private final List<ConfiguredPlugin> configuredPlugins;
  private final HarnessCatalog harnessCatalog;

  /** 构建期由产品目录投影一次性冻结，快照发布前完成赋值，此后只读。 */
  private Object productCatalog;

  private final Object lifecycleLock = new Object();
  private boolean closed;

  private PluginCatalogSnapshot(
      List<ConfiguredPlugin> configuredPlugins, HarnessCatalog harnessCatalog) {
    this.configuredPlugins = configuredPlugins;
    this.harnessCatalog = harnessCatalog;
  }

  /**
   * 按每个已安装 Plugin 的当前配置装配一次快照。
   *
   * @param configurations 必须覆盖全部已安装 pluginId
   * @param contexts 按 pluginId 提供 scoped {@link PluginContext}
   * @param productCatalog 由产品模块提供的目录投影：输入是本次已配置好的快照，输出是该产品唯一冻结的目录视图
   * @throws IllegalStateException 配置缺失、configure 失败或任一目录（含产品目录）校验失败时（只带 stage 与 pluginId）
   */
  public static PluginCatalogSnapshot build(
      StudioPluginRegistry registry,
      Map<String, PluginConfig> configurations,
      Function<String, PluginContext> contexts,
      List<HarnessContributor> builtins,
      Function<PluginCatalogSnapshot, ?> productCatalog) {
    Objects.requireNonNull(productCatalog, "productCatalog");
    List<ConfiguredPlugin> configured = new ArrayList<>();
    List<HarnessContributor> contributors = new ArrayList<>(builtins);
    String stage = "configure";
    String activeId = null;
    try {
      for (StudioPlugin plugin : registry.plugins()) {
        activeId = plugin.descriptor().pluginId();
        stage = "configure";
        plugin.authenticationSpec().requireConsistentWith(plugin.authHandler());
        plugin.configurationSchema().requireDeclaredSecrets(plugin.authenticationSpec());
        PluginConfig stored = configurations.get(activeId);
        if (stored == null) {
          throw new IllegalStateException("plugin configuration is missing");
        }
        PluginConfig config =
            new PluginConfig(
                stored.revision(), plugin.configurationSchema().resolveValues(stored.valuesJson()));
        PluginInstance instance =
            Objects.requireNonNull(
                plugin.configure(config, contexts.apply(activeId)), "plugin configure result");
        ConfiguredPlugin entry = new ConfiguredPlugin(plugin.descriptor(), config, instance);
        configured.add(entry);
        stage = "collect-contributions";
        contributors.addAll(instance.harnessContributors());
      }
      stage = "catalog";
      activeId = null;
      PluginCatalogSnapshot candidate =
          new PluginCatalogSnapshot(List.copyOf(configured), HarnessCatalog.from(contributors));
      candidate.productCatalog =
          Objects.requireNonNull(productCatalog.apply(candidate), "product catalog view");
      return candidate;
    } catch (RuntimeException error) {
      log.warn(
          "Plugin catalog snapshot build failed at stage {} for plugin {}: {}",
          stage,
          activeId,
          error.getClass().getSimpleName());
      closeAll(configured);
      // 原始异常可能携带插件或上游返回的敏感细节，只暴露阶段与 pluginId，不挂接 cause。
      String suffix = activeId == null ? "" : " pluginId=" + activeId;
      throw new IllegalStateException(
          "Plugin catalog snapshot build failed: stage=" + stage + suffix);
    } catch (Error fatal) {
      try {
        closeAll(configured);
      } catch (Error ignored) {
        // 原始致命错误优先，回收期的次要错误不替代它。
      }
      throw fatal;
    }
  }

  /** 按安装顺序排列的已配置 Plugin。 */
  public List<ConfiguredPlugin> configuredPlugins() {
    return configuredPlugins;
  }

  /** 插件贡献与内置贡献合并后的完整 Harness 目录。 */
  public HarnessCatalog harnessCatalog() {
    return harnessCatalog;
  }

  /**
   * 读取本次快照冻结的产品目录视图。
   *
   * @param type 产品目录视图类型
   * @throws IllegalStateException 本次快照的产品目录视图不是该类型时（装配错误，不是运行期分支）
   */
  public <T> T productCatalog(Class<T> type) {
    Objects.requireNonNull(type, "type");
    Object view = productCatalog;
    if (!type.isInstance(view)) {
      throw new IllegalStateException(
          "plugin catalog snapshot has no " + type.getSimpleName() + " product catalog");
    }
    return type.cast(view);
  }

  /** 每个 Plugin 当前应用的配置修订，用于判断是否需要重建。 */
  public Map<String, Long> revisions() {
    Map<String, Long> revisions = new LinkedHashMap<>();
    for (ConfiguredPlugin plugin : configuredPlugins) {
      revisions.put(plugin.descriptor().pluginId(), plugin.config().revision());
    }
    return revisions;
  }

  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    closeAll(configuredPlugins);
  }

  /** 逆序关闭全部实例；单个实例失败不阻断其余回收，首个致命错误在全部回收后抛出。 */
  private static void closeAll(List<ConfiguredPlugin> plugins) {
    Error fatal = null;
    for (int index = plugins.size() - 1; index >= 0; index--) {
      ConfiguredPlugin plugin = plugins.get(index);
      try {
        plugin.instance().close();
      } catch (RuntimeException error) {
        log.warn(
            "Plugin instance close failed for {}: {}",
            plugin.descriptor().pluginId(),
            error.getClass().getName());
      } catch (Error error) {
        if (fatal == null) {
          fatal = error;
        }
      }
    }
    if (fatal != null) {
      throw fatal;
    }
  }

  /** 一次配置产出的、由本快照拥有的运行期实例。 */
  public record ConfiguredPlugin(
      PluginDescriptor descriptor, PluginConfig config, PluginInstance instance) {

    public ConfiguredPlugin {
      Objects.requireNonNull(descriptor, "descriptor");
      Objects.requireNonNull(config, "config");
      Objects.requireNonNull(instance, "instance");
    }

    /** 该实例贡献的 ContributorId。 */
    public Set<ContributorId> contributorIds() {
      Set<ContributorId> ids = new LinkedHashSet<>();
      for (HarnessContributor contributor : instance.harnessContributors()) {
        ids.add(contributor.descriptor().id());
      }
      return ids;
    }
  }
}
