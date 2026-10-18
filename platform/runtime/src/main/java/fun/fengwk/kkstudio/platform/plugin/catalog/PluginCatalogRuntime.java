package fun.fengwk.kkstudio.platform.plugin.catalog;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 本节点当前 Plugin 快照的所有者。
 *
 * <p>启动时为新安装的 Plugin 写入默认配置并构建首个快照；{@link #refresh()} 回读全部配置，修订有变化时构建新快照并原子替换，构建失败时保留旧快照并记录 {@link
 * #applicationError()}。快照按引用计数退役：被替换的快照在最后一个租用释放后才关闭，因此在途调用不会看到实例被关闭。
 */
@Slf4j
public final class PluginCatalogRuntime implements AutoCloseable {

  private final StudioPluginRegistry registry;
  private final PluginConfigurationRepository repository;
  private final Function<String, PluginContext> contexts;
  private final List<HarnessContributor> builtins;
  private final List<CanvasFunctionAdapter> builtinAdapters;

  private final Object lifecycleLock = new Object();
  private final Object refreshLock = new Object();

  private View current;
  private String applicationError;
  private boolean closed;

  public PluginCatalogRuntime(
      StudioPluginRegistry registry,
      PluginConfigurationRepository repository,
      Function<String, PluginContext> contexts,
      List<HarnessContributor> builtins,
      List<CanvasFunctionAdapter> builtinAdapters) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.builtins = List.copyOf(builtins);
    this.builtinAdapters = List.copyOf(builtinAdapters);
    for (StudioPlugin plugin : registry.plugins()) {
      repository.insertIfMissing(
          plugin.descriptor().pluginId(), plugin.configurationSchema().defaultsJson());
    }
    this.current = new View(build(readConfigurations()));
  }

  /** 回读配置；有修订变化时重建并替换当前快照。失败保留旧快照。 */
  public void refresh() {
    synchronized (refreshLock) {
      Map<String, PluginConfig> configurations;
      PluginCatalogSnapshot candidate;
      try {
        configurations = readConfigurations();
        if (revisionsOf(configurations).equals(currentRevisions())) {
          recordError(null);
          return;
        }
        candidate = build(configurations);
      } catch (RuntimeException error) {
        log.warn("Plugin catalog refresh failed: {}", error.getClass().getSimpleName());
        recordError("plugin configuration could not be applied on this node");
        return;
      }
      publish(candidate);
    }
  }

  /** 租用当前快照；租用期间该快照不会被关闭。 */
  public Lease acquire() {
    synchronized (lifecycleLock) {
      if (closed) {
        throw new IllegalStateException("plugin catalog runtime is closed");
      }
      current.holders++;
      return new Lease(current);
    }
  }

  /** 最近一次刷新失败的说明；已同步时为 {@code null}。 */
  public String applicationError() {
    synchronized (lifecycleLock) {
      return applicationError;
    }
  }

  @Override
  public void close() {
    PluginCatalogSnapshot released;
    synchronized (lifecycleLock) {
      if (closed) {
        return;
      }
      closed = true;
      released = current.release();
    }
    if (released != null) {
      released.close();
    }
  }

  private PluginCatalogSnapshot build(Map<String, PluginConfig> configurations) {
    return PluginCatalogSnapshot.build(
        registry, configurations, contexts, builtins, builtinAdapters);
  }

  private void publish(PluginCatalogSnapshot candidate) {
    PluginCatalogSnapshot retired;
    synchronized (lifecycleLock) {
      if (closed) {
        retired = candidate;
      } else {
        retired = current.release();
        current = new View(candidate);
        applicationError = null;
      }
    }
    if (retired != null) {
      retired.close();
    }
  }

  private Map<String, PluginConfig> readConfigurations() {
    Map<String, PluginConfig> configurations = new LinkedHashMap<>();
    for (PluginConfigurationRow row : repository.findAll()) {
      if (registry.find(row.pluginId()).isPresent()) {
        configurations.put(row.pluginId(), new PluginConfig(row.revision(), row.valuesJson()));
      }
    }
    return configurations;
  }

  private Map<String, Long> currentRevisions() {
    synchronized (lifecycleLock) {
      return current.snapshot.revisions();
    }
  }

  private static Map<String, Long> revisionsOf(Map<String, PluginConfig> configurations) {
    Map<String, Long> revisions = new LinkedHashMap<>();
    configurations.forEach((pluginId, config) -> revisions.put(pluginId, config.revision()));
    return revisions;
  }

  private void recordError(String error) {
    synchronized (lifecycleLock) {
      applicationError = error;
    }
  }

  /** 一个已发布快照：1 个所有者引用加若干租用，计数归零时恰好关闭一次。 */
  private static final class View {

    private final PluginCatalogSnapshot snapshot;
    private int holders = 1;

    private View(PluginCatalogSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    /** 释放一个引用；归零时返回需要在锁外关闭的快照。 */
    private PluginCatalogSnapshot release() {
      holders--;
      return holders == 0 ? snapshot : null;
    }
  }

  /** 快照租用：{@link #close()} 幂等，最后一个引用释放时快照关闭。 */
  public final class Lease implements AutoCloseable {

    private final View view;
    private boolean released;

    private Lease(View view) {
      this.view = view;
    }

    public PluginCatalogSnapshot snapshot() {
      return view.snapshot;
    }

    @Override
    public void close() {
      PluginCatalogSnapshot retired;
      synchronized (lifecycleLock) {
        if (released) {
          return;
        }
        released = true;
        retired = view.release();
      }
      if (retired != null) {
        retired.close();
      }
    }
  }
}
