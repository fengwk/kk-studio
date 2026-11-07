package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canvas Function 目录的生产实现：每次租用钉住 {@link PluginCatalogRuntime} 的当前快照，并读取其中已冻结的 {@link
 * PluginCanvasFunctionCatalog} 目录。
 *
 * <p>每个 {@link Lease} 固定当前函数目录，租用期间其中的 adapter 实例不会被关闭；执行时通过 {@link
 * Lease#require(CanvasFunctionBinding)} 取回定义一致的注册项。
 */
public final class PluginCanvasFunctionCatalogSource {

  private final PluginCatalogRuntime runtime;

  public PluginCanvasFunctionCatalogSource(PluginCatalogRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  /** 租用当前目录。 */
  public Lease acquire() {
    return new SnapshotLease(runtime.acquire());
  }

  /** 一次目录租用；{@link #close()} 不抛受检异常。 */
  public interface Lease extends AutoCloseable {

    /** 本次租用的目录。 */
    CanvasFunctionCatalog catalog();

    /**
     * 取回与绑定定义一致的注册项。
     *
     * @throws IllegalStateException 函数已不存在或定义已变化
     */
    CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding);

    @Override
    void close();
  }

  private static final class SnapshotLease implements Lease {

    private final PluginCatalogRuntime.Lease lease;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SnapshotLease(PluginCatalogRuntime.Lease lease) {
      this.lease = lease;
    }

    @Override
    public CanvasFunctionCatalog catalog() {
      requireOpen();
      return PluginCanvasFunctionCatalog.of(lease.snapshot()).catalog();
    }

    @Override
    public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
      Objects.requireNonNull(binding, "binding");
      CanvasFunctionCatalog.RegisteredFunction registered =
          catalog()
              .find(binding.definition().name())
              .orElseThrow(
                  () -> new IllegalStateException("Canvas Function is no longer available"));
      if (!registered.function().equals(binding.definition())) {
        throw new IllegalStateException("Canvas Function definition changed");
      }
      return registered;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        lease.close();
      }
    }

    private void requireOpen() {
      if (closed.get()) {
        throw new IllegalStateException("canvas function catalog lease is closed");
      }
    }
  }
}
