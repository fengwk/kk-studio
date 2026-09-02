package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;

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
  public Optional<ToolContribution> findTool(AgentToolId id) {
    return catalog.findTool(Objects.requireNonNull(id, "id"));
  }
}
