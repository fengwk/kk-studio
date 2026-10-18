package fun.fengwk.kkstudio.platform.canvas.function;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Canvas Function 目录的生产实现：每次租用钉住 {@link PluginCatalogRuntime} 的当前快照。 */
public final class PluginCanvasFunctionCatalogSource implements CanvasFunctionCatalogSource {

  private final PluginCatalogRuntime runtime;

  public PluginCanvasFunctionCatalogSource(PluginCatalogRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
  }

  @Override
  public Lease acquire() {
    return new SnapshotLease(runtime.acquire());
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
      return lease.snapshot().canvasFunctionCatalog();
    }

    @Override
    public CanvasFunctionBinding bind(String functionName) {
      return new CanvasFunctionBinding(catalog().require(functionName).function());
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
