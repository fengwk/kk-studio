package fun.fengwk.kkstudio.platform.catalog.tool;

import static org.mockito.Mockito.mock;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolPluginSource;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 组件测试用的单 epoch {@link RuntimeToolCatalogSource}：构造时冻结给定两域目录，不推导任何插件来源。
 *
 * <p>它只替代 epoch 对账与快照所有权，冻结索引与严格校验都直接复用生产实现（{@link
 * PluginRuntimeToolCatalogSource.FrozenRuntimeToolCatalog}、{@link FrozenToolBindingVerifier}），因此
 * test double 不会与生产语义漂移。租用开关计数用于断言租用确实跨越完整执行生命周期。
 */
public final class TestRuntimeToolCatalogSource implements RuntimeToolCatalogSource {

  /** 测试目录默认 epoch。 */
  public static final long EPOCH = 1L;

  private final long epoch;
  private final HarnessCatalog harnessCatalog;
  private final RuntimeToolCatalog catalog;
  private final AtomicInteger openLeases = new AtomicInteger();
  private final AtomicInteger closedLeases = new AtomicInteger();

  private TestRuntimeToolCatalogSource(
      long epoch, HarnessCatalog harnessCatalog, RuntimeToolCatalog catalog) {
    this.epoch = epoch;
    this.harnessCatalog = Objects.requireNonNull(harnessCatalog, "harnessCatalog");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  /** 冻结给定 Harness 目录（无 MCP 工具）的 source。 */
  public static TestRuntimeToolCatalogSource of(HarnessCatalog harnessCatalog) {
    return of(harnessCatalog, emptyMcpCatalog());
  }

  /** 冻结给定 Harness 目录与 MCP 目录的 source。 */
  public static TestRuntimeToolCatalogSource of(
      HarnessCatalog harnessCatalog, McpToolCatalog mcpCatalog) {
    return of(harnessCatalog, mcpCatalog, EPOCH);
  }

  /** 冻结给定两域目录并指定 epoch 的 source：用于 epoch 漂移场景。 */
  public static TestRuntimeToolCatalogSource of(
      HarnessCatalog harnessCatalog, McpToolCatalog mcpCatalog, long epoch) {
    return new TestRuntimeToolCatalogSource(
        epoch,
        harnessCatalog,
        PluginRuntimeToolCatalogSource.FrozenRuntimeToolCatalog.freeze(harnessCatalog, mcpCatalog));
  }

  /** 只含 MCP 工具的 source。 */
  public static TestRuntimeToolCatalogSource ofMcp(McpToolCatalog mcpCatalog) {
    return of(HarnessCatalog.from(List.of()), mcpCatalog);
  }

  /** 不读库的空 MCP 目录：仅用于不需要 MCP 工具的测试。 */
  public static McpToolCatalog emptyMcpCatalog() {
    return new McpToolCatalog(mock(McpServerRepository.class), mock(ExecutorService.class));
  }

  /** 已授予的租用总数。 */
  public int openLeases() {
    return openLeases.get();
  }

  /** 已释放的租用总数：与 {@link #openLeases()} 之差就是仍在生效的目录租用。 */
  public int closedLeases() {
    return closedLeases.get();
  }

  @Override
  public Lease acquireCurrent() {
    return acquire(epoch);
  }

  @Override
  public Lease acquire(long catalogEpoch) {
    if (catalogEpoch != epoch) {
      throw new IllegalArgumentException("catalog epoch is not available: " + catalogEpoch);
    }
    openLeases.incrementAndGet();
    return new Lease();
  }

  private final class Lease implements RuntimeToolCatalogSource.Lease {

    private boolean closed;

    @Override
    public long epoch() {
      return epoch;
    }

    @Override
    public HarnessCatalog harnessCatalog() {
      return harnessCatalog;
    }

    @Override
    public RuntimeToolCatalog catalog() {
      return catalog;
    }

    @Override
    public ToolPluginSource pluginSource(ContributionId contributionId) {
      return null;
    }

    @Override
    public ToolContribution require(ToolBinding binding) {
      if (closed) {
        throw new IllegalStateException("runtime tool catalog lease is closed");
      }
      return FrozenToolBindingVerifier.require(epoch, catalog, Map.of(), binding);
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        closedLeases.incrementAndGet();
      }
    }
  }
}
