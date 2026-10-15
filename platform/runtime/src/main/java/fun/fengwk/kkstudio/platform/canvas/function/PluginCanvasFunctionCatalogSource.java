package fun.fengwk.kkstudio.platform.canvas.function;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionPluginSource;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canvas Function 目录的生产 {@link CanvasFunctionCatalogSource}：把每一次租用直接钉在 {@link PluginCatalogRuntime}
 * 的一个冻结 epoch 上，因此目录、函数定义与插件来源恒来自同一份快照，绝不按函数名退回当前版本。
 *
 * <p>插件来源逐个取自 owner map 指出的冻结实例：安装身份与代码版本来自该实例 descriptor，配置修订来自其归一化配置，能力指纹来自快照的
 * capabilityFingerprints，认证代际与上下文指纹来自其凭据引用；内置函数不属于任何插件，来源为 {@code null}。这里只记录身份事实，不读取任何明文或密文凭据。
 */
public final class PluginCanvasFunctionCatalogSource implements CanvasFunctionCatalogSource {

  private final PluginCatalogRuntime runtime;

  public PluginCanvasFunctionCatalogSource(PluginCatalogRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public Lease acquireCurrent() {
    return new SnapshotLease(runtime.acquireCurrent());
  }

  @Override
  public Lease acquire(long catalogEpoch) {
    return new SnapshotLease(runtime.acquire(catalogEpoch));
  }

  /** 一次目录租用：构造期一次冻结函数名到插件来源的映射，失败即释放底层租用。 */
  private static final class SnapshotLease implements Lease {

    private final PluginCatalogRuntime.Lease lease;

    private final Map<String, CanvasFunctionPluginSource> pluginSources;

    private final AtomicBoolean closed = new AtomicBoolean();

    SnapshotLease(PluginCatalogRuntime.Lease lease) {
      this.lease = lease;
      Map<String, CanvasFunctionPluginSource> sources;
      try {
        sources = pluginSources(lease.snapshot());
      } catch (RuntimeException | Error primary) {
        closePreservingPrimary(primary);
        throw primary;
      }
      this.pluginSources = sources;
    }

    @Override
    public long epoch() {
      requireOpen();
      return lease.snapshot().epoch();
    }

    @Override
    public CanvasFunctionCatalog catalog() {
      requireOpen();
      return lease.snapshot().canvasFunctionCatalog();
    }

    @Override
    public CanvasFunctionBinding bind(String functionName) {
      requireOpen();
      PluginCatalogSnapshot snapshot = lease.snapshot();
      try {
        CanvasFunctionCatalog.RegisteredFunction registered =
            snapshot.canvasFunctionCatalog().require(functionName);
        return new CanvasFunctionBinding(
            snapshot.epoch(), registered.function(), pluginSources.get(functionName));
      } catch (RuntimeException | Error primary) {
        closePreservingPrimary(primary);
        throw primary;
      }
    }

    @Override
    public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
      requireOpen();
      PluginCatalogSnapshot snapshot = lease.snapshot();
      try {
        Objects.requireNonNull(binding, "binding");
        if (binding.catalogEpoch() != snapshot.epoch()) {
          throw new IllegalArgumentException("binding epoch does not match the acquired catalog");
        }
        String functionName = binding.definition().name();
        CanvasFunctionCatalog.RegisteredFunction registered =
            snapshot.canvasFunctionCatalog().require(functionName);
        if (!registered.function().equals(binding.definition())) {
          throw new IllegalArgumentException(
              "binding definition does not match the acquired catalog");
        }
        if (!Objects.equals(pluginSources.get(functionName), binding.pluginSource())) {
          throw new IllegalArgumentException(
              "binding plugin source does not match the acquired catalog");
        }
        return registered;
      } catch (RuntimeException | Error primary) {
        closePreservingPrimary(primary);
        throw primary;
      }
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        lease.close();
      }
    }

    /** 释放底层租用但保留原始致命/失败原因：清理期的次要异常绝不遮蔽它，也不重复压制自身。 */
    private void closePreservingPrimary(Throwable primary) {
      try {
        close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != primary) {
          primary.addSuppressed(cleanup);
        }
      }
    }

    private void requireOpen() {
      if (closed.get()) {
        throw new IllegalStateException("canvas function catalog lease is closed");
      }
    }
  }

  /** 从一次冻结快照派生函数名到插件来源的映射；不属任何插件的函数是内置函数，不出现在映射中。 */
  private static Map<String, CanvasFunctionPluginSource> pluginSources(
      PluginCatalogSnapshot snapshot) {
    Map<String, PluginCatalogSnapshot.ConfiguredPlugin> configured = new LinkedHashMap<>();
    for (PluginCatalogSnapshot.ConfiguredPlugin plugin : snapshot.configuredPlugins()) {
      configured.put(plugin.descriptor().pluginId(), plugin);
    }
    Map<String, CanvasFunctionPluginSource> sources = new LinkedHashMap<>();
    for (Map.Entry<String, PluginCatalogSnapshot.PluginOwnership> entry :
        snapshot.pluginOwnership().entrySet()) {
      String pluginId = entry.getKey();
      PluginCatalogSnapshot.ConfiguredPlugin plugin = configured.get(pluginId);
      CanvasFunctionPluginSource source =
          new CanvasFunctionPluginSource(
              pluginId,
              plugin.descriptor().version(),
              plugin.config().revision(),
              snapshot.capabilityFingerprints().get(pluginId),
              plugin.credentialReference().authGeneration(),
              plugin.credentialReference().contextFingerprint());
      for (String functionName : entry.getValue().functionNames()) {
        sources.put(functionName, source);
      }
    }
    return Map.copyOf(sources);
  }
}
