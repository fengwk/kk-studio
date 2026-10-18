package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试替身：持有一份可替换的当前目录，租用严格校验完整定义，并记录仍未关闭的租用数。
 *
 * <p>用它可以断言两个生产事实：冻结 binding 只能按同一定义取回 adapter；{@code start} 的租用必须延续到真实事务的 afterCompletion 才释放。
 */
public final class FixedCanvasFunctionCatalogSource implements CanvasFunctionCatalogSource {

  private final AtomicInteger openLeases = new AtomicInteger();
  private final AtomicInteger acquireCalls = new AtomicInteger();
  private volatile CanvasFunctionCatalog current;

  public FixedCanvasFunctionCatalogSource(CanvasFunctionCatalog catalog) {
    replace(catalog);
  }

  /** 替换之后租用看到的当前目录；已开启的租用继续持有旧目录。 */
  public void replace(CanvasFunctionCatalog catalog) {
    this.current = Objects.requireNonNull(catalog, "catalog");
  }

  /** 仍未 close 的租用数；用于断言租用确实随事务结束释放。 */
  public int openLeases() {
    return openLeases.get();
  }

  /** {@code acquire()} 的调用次数；用于断言精确重放完全不取租用。 */
  public int acquireCalls() {
    return acquireCalls.get();
  }

  @Override
  public Lease acquire() {
    acquireCalls.incrementAndGet();
    return new Lease(current);
  }

  private final class Lease implements CanvasFunctionCatalogSource.Lease {

    private final CanvasFunctionCatalog catalog;
    private boolean closed;

    private Lease(CanvasFunctionCatalog catalog) {
      this.catalog = catalog;
      openLeases.incrementAndGet();
    }

    @Override
    public CanvasFunctionCatalog catalog() {
      return catalog;
    }

    @Override
    public CanvasFunctionBinding bind(String functionName) {
      return new CanvasFunctionBinding(catalog.require(functionName).function());
    }

    @Override
    public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
      CanvasFunctionCatalog.RegisteredFunction registered =
          catalog
              .find(binding.definition().name())
              .orElseThrow(() -> new IllegalStateException("function is no longer available"));
      if (!registered.function().equals(binding.definition())) {
        throw new IllegalStateException("function definition changed");
      }
      return registered;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      openLeases.decrementAndGet();
    }
  }
}
