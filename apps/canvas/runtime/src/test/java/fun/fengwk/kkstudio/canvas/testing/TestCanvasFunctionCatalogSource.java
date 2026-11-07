package fun.fengwk.kkstudio.canvas.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.infra.platform.PluginCanvasFunctionCatalogSource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试目录源：用 Mockito 构造 {@link PluginCanvasFunctionCatalogSource} 替身，持有一份可替换的当前目录。
 *
 * <p>每次租用按当前目录取快照，{@link PluginCanvasFunctionCatalogSource.Lease#close()} 只释放一次，并记录仍未关闭的租用数与
 * acquire 次数。模块测试因此可以断言两个生产事实：冻结 binding 只能按同一定义取回 adapter；{@code start} 的租用必须延续到真实事务的
 * afterCompletion 才释放。
 */
public final class TestCanvasFunctionCatalogSource {

  private final AtomicInteger openLeases = new AtomicInteger();
  private final AtomicInteger acquireCalls = new AtomicInteger();
  private final PluginCanvasFunctionCatalogSource source =
      mock(PluginCanvasFunctionCatalogSource.class);
  private volatile CanvasFunctionCatalog current;

  public TestCanvasFunctionCatalogSource(CanvasFunctionCatalog catalog) {
    replace(catalog);
    when(source.acquire()).thenAnswer(invocation -> acquire());
  }

  /** 注入生产协作者的真实类型。 */
  public PluginCanvasFunctionCatalogSource source() {
    return source;
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

  private PluginCanvasFunctionCatalogSource.Lease acquire() {
    acquireCalls.incrementAndGet();
    CanvasFunctionCatalog catalog = current;
    PluginCanvasFunctionCatalogSource.Lease lease =
        mock(PluginCanvasFunctionCatalogSource.Lease.class);
    AtomicBoolean closed = new AtomicBoolean();
    when(lease.catalog())
        .thenAnswer(
            invocation -> {
              if (closed.get()) {
                throw new IllegalStateException("canvas function catalog lease is closed");
              }
              return catalog;
            });
    when(lease.require(any()))
        .thenAnswer(invocation -> require(lease.catalog(), invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              if (closed.compareAndSet(false, true)) {
                openLeases.decrementAndGet();
              }
              return null;
            })
        .when(lease)
        .close();
    openLeases.incrementAndGet();
    return lease;
  }

  /** 与生产租用一致：函数已移除或定义已变化时拒绝，绝不退回当前同名函数。 */
  private static CanvasFunctionCatalog.RegisteredFunction require(
      CanvasFunctionCatalog catalog, CanvasFunctionBinding binding) {
    CanvasFunctionCatalog.RegisteredFunction registered =
        catalog
            .find(binding.definition().name())
            .orElseThrow(() -> new IllegalStateException("function is no longer available"));
    if (!registered.function().equals(binding.definition())) {
      throw new IllegalStateException("function definition changed");
    }
    return registered;
  }
}
