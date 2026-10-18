package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生产 {@link RuntimeToolCatalogSource}：直接使用 {@link PluginCatalogRuntime} 的引用计数租用，不引入第二份 registry
 * 或缓存。
 *
 * <p>每次租用把同一 snapshot 的完整 Harness 工具（含内部工具）与一次性读取的全部 MCP 工具冻成只读索引：模型可见 name 就是身份， 因此同名即同一身份，重复一律
 * fail-closed。MCP 是独立的数据库动态事实，本次租用只读一次库即冻结 planning 面与选择面，调用期 server 可用性仍由执行边界 fail closed。
 */
public final class PluginRuntimeToolCatalogSource implements RuntimeToolCatalogSource {

  private final PluginCatalogRuntime runtime;
  private final McpToolCatalog mcpCatalog;

  public PluginRuntimeToolCatalogSource(PluginCatalogRuntime runtime, McpToolCatalog mcpCatalog) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.mcpCatalog = Objects.requireNonNull(mcpCatalog, "mcpCatalog");
  }

  @Override
  public Lease acquire() {
    return open(runtime.acquire());
  }

  /**
   * 用已取得的 runtime 租用构建冻结视图；冻结失败（含目录冲突或快照事实不一致）时先释放该租用再向上抛出。
   *
   * <p>主异常原样传播；不同实例的清理异常附加为 suppressed，同实例不重复附加。
   */
  private Lease open(PluginCatalogRuntime.Lease snapshotLease) {
    try {
      return new Lease(snapshotLease, mcpCatalog);
    } catch (RuntimeException | Error failure) {
      try {
        snapshotLease.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failure) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
  }

  /** 单次目录租用：当前快照的 Harness 目录与一次性读取的 MCP 事实。 */
  static final class Lease implements RuntimeToolCatalogSource.Lease {

    private final PluginCatalogRuntime.Lease snapshotLease;
    private final PluginCatalogSnapshot snapshot;
    private final FrozenRuntimeToolCatalog catalog;
    private final AtomicBoolean closed = new AtomicBoolean();

    Lease(PluginCatalogRuntime.Lease snapshotLease, McpToolCatalog mcpCatalog) {
      this.snapshotLease = Objects.requireNonNull(snapshotLease, "snapshotLease");
      this.snapshot = snapshotLease.snapshot();
      this.catalog = FrozenRuntimeToolCatalog.freeze(snapshot.harnessCatalog(), mcpCatalog);
    }

    @Override
    public HarnessCatalog harnessCatalog() {
      requireOpen();
      return snapshot.harnessCatalog();
    }

    @Override
    public RuntimeToolCatalog catalog() {
      requireOpen();
      return catalog;
    }

    @Override
    public ToolContribution require(ToolBinding binding) {
      requireOpen();
      return FrozenToolBindingVerifier.require(catalog, binding);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        snapshotLease.close();
      }
    }

    private void requireOpen() {
      if (closed.get()) {
        throw new IllegalStateException("runtime tool catalog lease is closed");
      }
    }
  }

  /**
   * 一次租用的只读运行时工具目录视图：固定同一 snapshot 的 Harness 工具与同一次现读的全部 MCP 工具。
   *
   * <p>{@link #selectableTools()} 与 {@link #findTool(String)} 出自同一冻结索引，绝不二次读库；MCP 的全部 plannable
   * 行都进入 findTool 索引，因此被禁用/发现失败的 server 下已持久化定义仍然可在 planning 期解析，调用期可用性仍由执行边界 fail closed。
   */
  static final class FrozenRuntimeToolCatalog implements RuntimeToolCatalog {

    private final Map<String, ToolContribution> frozenByName;
    private final List<ToolContribution> selectableTools;

    private FrozenRuntimeToolCatalog(
        Map<String, ToolContribution> frozenByName, List<ToolContribution> selectableTools) {
      this.frozenByName = frozenByName;
      this.selectableTools = selectableTools;
    }

    static FrozenRuntimeToolCatalog freeze(
        HarnessCatalog harnessCatalog, McpToolCatalog mcpCatalog) {
      Objects.requireNonNull(harnessCatalog, "harnessCatalog");
      Objects.requireNonNull(mcpCatalog, "mcpCatalog");
      McpToolCatalog.Snapshot mcp = mcpCatalog.readSnapshot();
      Map<String, ToolContribution> frozen = new LinkedHashMap<>();
      for (ToolContribution tool : harnessCatalog.tools()) {
        frozen.put(tool.definition().descriptor().name(), tool);
      }
      for (ToolContribution tool : mcp.plannableTools()) {
        String name = tool.definition().descriptor().name();
        if (frozen.putIfAbsent(name, tool) != null) {
          throw new IllegalStateException("duplicate tool name across catalogs: " + name);
        }
      }
      List<ToolContribution> selectable = new ArrayList<>(harnessCatalog.selectableTools());
      selectable.addAll(mcp.selectableTools());
      return new FrozenRuntimeToolCatalog(Map.copyOf(frozen), List.copyOf(selectable));
    }

    @Override
    public List<ToolContribution> selectableTools() {
      return selectableTools;
    }

    @Override
    public Optional<ToolContribution> findTool(String toolName) {
      Objects.requireNonNull(toolName, "toolName");
      return Optional.ofNullable(frozenByName.get(toolName));
    }
  }
}
