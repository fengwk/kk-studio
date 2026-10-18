package fun.fengwk.kkstudio.platform.catalog.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;

import java.util.List;
import java.util.Optional;

/**
 * Platform 侧 runtime tool catalog 的只读视图：暴露一次租用内的可执行 {@link ToolContribution}。
 *
 * <p>生产视图由 {@link RuntimeToolCatalogSource.Lease#catalog()} 提供：租用快照中的 Harness 工具与本次一次性读取的 MCP
 * 可选工具。视图没有自身的租用语义，消费者必须先从 {@link RuntimeToolCatalogSource} 取得租用再读它。
 */
public interface RuntimeToolCatalog {

  /** 返回当前全部可选择工具贡献；顺序稳定。 */
  List<ToolContribution> selectableTools();

  /** 按模型可见 tool name 查找工具贡献。 */
  Optional<ToolContribution> findTool(String toolName);
}
