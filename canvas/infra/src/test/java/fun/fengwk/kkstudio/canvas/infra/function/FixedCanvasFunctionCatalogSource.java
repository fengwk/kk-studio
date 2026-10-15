package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalogSource;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionPluginSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试替身：按显式 epoch 注册冻结目录，租用严格校验 epoch、完整定义与插件来源，并记录仍未关闭的租用数。
 *
 * <p>用它可以断言两个生产事实：冻结 binding 只能按同 epoch 的同一定义与来源取回 adapter；{@code start} 的 current 租用必须延续到 真实事务的
 * afterCompletion 才释放。
 */
public final class FixedCanvasFunctionCatalogSource implements CanvasFunctionCatalogSource {

  private final Map<Long, CanvasFunctionCatalog> catalogsByEpoch = new LinkedHashMap<>();
  private final Map<Long, Map<String, CanvasFunctionPluginSource>> pluginSourcesByEpoch =
      new LinkedHashMap<>();
  private final AtomicInteger openLeases = new AtomicInteger();
  private final AtomicInteger acquireCurrentCalls = new AtomicInteger();
  private volatile long currentEpoch;

  public FixedCanvasFunctionCatalogSource(long epoch, CanvasFunctionCatalog catalog) {
    register(epoch, catalog);
    this.currentEpoch = epoch;
  }

  /** 注册一个 epoch 的冻结目录，供之后 {@code acquire(epoch)} 或切换当前 epoch 使用。 */
  public void register(long epoch, CanvasFunctionCatalog catalog) {
    catalogsByEpoch.put(epoch, Objects.requireNonNull(catalog, "catalog"));
  }

  /** 切换 {@code acquireCurrent()} 返回的 epoch。 */
  public void currentEpoch(long epoch) {
    if (!catalogsByEpoch.containsKey(epoch)) {
      throw new IllegalArgumentException("catalog epoch is not registered: " + epoch);
    }
    this.currentEpoch = epoch;
  }

  /**
   * 把某个 epoch 下某个函数名标注为插件来源；未标注即内置函数（pluginSource 为 null）。
   *
   * <p>来源身份属于该 epoch 的冻结目录：新 epoch 换了来源不会改写旧 epoch 的租用语义。
   */
  public void pluginSource(long epoch, String functionName, CanvasFunctionPluginSource source) {
    pluginSourcesByEpoch
        .computeIfAbsent(epoch, ignored -> new LinkedHashMap<>())
        .put(functionName, Objects.requireNonNull(source, "source"));
  }

  private CanvasFunctionPluginSource pluginSource(long epoch, String functionName) {
    return pluginSourcesByEpoch.getOrDefault(epoch, Map.of()).get(functionName);
  }

  /** 仍未 close 的租用数；用于断言租用确实随事务结束释放。 */
  public int openLeases() {
    return openLeases.get();
  }

  /** {@code acquireCurrent()} 的调用次数；用于断言精确重放完全不取 current 租用。 */
  public int acquireCurrentCalls() {
    return acquireCurrentCalls.get();
  }

  @Override
  public Lease acquireCurrent() {
    acquireCurrentCalls.incrementAndGet();
    return acquire(currentEpoch);
  }

  @Override
  public Lease acquire(long catalogEpoch) {
    CanvasFunctionCatalog catalog = catalogsByEpoch.get(catalogEpoch);
    if (catalog == null) {
      throw new IllegalArgumentException("catalog epoch is not available: " + catalogEpoch);
    }
    return new Lease(catalogEpoch, catalog);
  }

  private final class Lease implements CanvasFunctionCatalogSource.Lease {

    private final long epoch;
    private final CanvasFunctionCatalog catalog;
    private boolean closed;

    private Lease(long epoch, CanvasFunctionCatalog catalog) {
      this.epoch = epoch;
      this.catalog = catalog;
      openLeases.incrementAndGet();
    }

    @Override
    public long epoch() {
      return epoch;
    }

    @Override
    public CanvasFunctionCatalog catalog() {
      return catalog;
    }

    @Override
    public CanvasFunctionBinding bind(String functionName) {
      CanvasFunctionCatalog.RegisteredFunction registered = catalog.require(functionName);
      return new CanvasFunctionBinding(
          epoch, registered.function(), pluginSource(epoch, functionName));
    }

    @Override
    public CanvasFunctionCatalog.RegisteredFunction require(CanvasFunctionBinding binding) {
      Objects.requireNonNull(binding, "binding");
      if (binding.catalogEpoch() != epoch) {
        throw new IllegalArgumentException("binding epoch does not match the acquired catalog");
      }
      String functionName = binding.definition().name();
      CanvasFunctionCatalog.RegisteredFunction registered = catalog.require(functionName);
      if (!registered.function().equals(binding.definition())) {
        throw new IllegalArgumentException(
            "binding definition does not match the acquired catalog");
      }
      if (!Objects.equals(pluginSource(epoch, functionName), binding.pluginSource())) {
        throw new IllegalArgumentException(
            "binding plugin source does not match the acquired catalog");
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
