package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 静态 HarnessCatalog 的 {@link RuntimeToolCatalog} 适配器：只读转发，无状态。 */
public final class HarnessToolCatalogAdapter implements RuntimeToolCatalog {

  private final HarnessCatalog catalog;

  public HarnessToolCatalogAdapter(HarnessCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public List<ToolContribution> selectableTools() {
    return catalog.selectableTools();
  }

  @Override
  public Optional<ToolContribution> findTool(String toolName) {
    return catalog.findTool(Objects.requireNonNull(toolName, "toolName"));
  }

  /** 按冻结贡献身份读取静态 Tool；该路径不会访问动态 MCP 目录。 */
  public Optional<ToolContribution> findTool(ContributionId contributionId) {
    return catalog.findTool(Objects.requireNonNull(contributionId, "contributionId"));
  }
}
