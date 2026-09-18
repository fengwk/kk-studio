package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;

import java.util.List;
import java.util.Optional;

/**
 * Platform 侧 runtime tool catalog SPI：按需暴露可执行的 {@link ToolContribution}。
 *
 * <p>生产提供两个源实现：静态 {@link HarnessToolCatalogAdapter}（包装冻结 HarnessCatalog）与动态 {@code
 * McpToolCatalog}（每次从 DB 现读 MCP 工具行并映射为可执行贡献）。{@link CompositeRuntimeToolCatalog}
 * 将两者统一为业务与运行时消费方使用的单一生产目录。
 */
public interface RuntimeToolCatalog {

  /** 返回当前全部可选择工具贡献；顺序稳定。 */
  List<ToolContribution> selectableTools();

  /** 按模型可见 tool name 查找工具贡献。 */
  Optional<ToolContribution> findTool(String toolName);
}
