package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;

/**
 * Platform 侧运行时工具目录的租用端口：每个 {@link Lease} 固定当前插件快照与一次性读取的 MCP 工具。
 *
 * <p>租用保护其中 Plugin 实例的生命周期：被替换的快照在最后一个租用释放后才关闭。
 */
public interface RuntimeToolCatalogSource {

  /** 租用当前目录。 */
  Lease acquire();

  interface Lease extends AutoCloseable {

    /** 本次租用的完整 Harness 目录（含内部工具）。 */
    HarnessCatalog harnessCatalog();

    /** 本次租用的运行时工具目录视图：Harness 工具与本次读取的 MCP 工具。 */
    RuntimeToolCatalog catalog();

    /**
     * 按名取回冻结 binding 对应的当前贡献，并要求完整定义、贡献身份与 requirements 一致。
     *
     * @throws ToolBindingMismatchException 工具已不存在或定义已变化
     */
    ToolContribution require(ToolBinding binding);

    @Override
    void close();
  }
}
