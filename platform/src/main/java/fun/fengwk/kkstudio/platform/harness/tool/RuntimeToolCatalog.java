package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.List;
import java.util.Optional;

/**
 * Platform 侧 runtime tool catalog SPI：按需暴露可执行的 {@link ToolContribution}。
 *
 * <p>两个实现：静态 {@link HarnessToolCatalogAdapter}（包装冻结 HarnessCatalog）与动态 {@code McpToolCatalog} （每次从
 * DB 现读 MCP 工具行并映射为可执行贡献）。四个既有 HarnessCatalog 消费者不在本切片修改，wiring 由后续 切片完成。
 */
public interface RuntimeToolCatalog {

  /** 返回当前全部可选择工具贡献；顺序稳定。 */
  List<ToolContribution> selectableTools();

  /** 按稳定 AgentToolId 查找工具贡献。 */
  Optional<ToolContribution> findTool(AgentToolId id);
}
