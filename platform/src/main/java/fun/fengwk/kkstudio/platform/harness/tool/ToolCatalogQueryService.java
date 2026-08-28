package fun.fengwk.kkstudio.platform.harness.tool;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.util.List;
import java.util.Objects;

/** 离线可选的 runtime tool catalog 的 Platform 边界。 */
@Service
public class ToolCatalogQueryService {

  private final HarnessCatalog catalog;

  public ToolCatalogQueryService(HarnessCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  public List<ToolCatalogEntryDTO> listTools() {
    return catalog.selectableTools().stream().map(ToolCatalogQueryService::toDto).toList();
  }

  private static ToolCatalogEntryDTO toDto(ToolContribution contribution) {
    ToolDescriptor descriptor = contribution.definition().descriptor();
    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setId(contribution.definition().id().value());
    dto.setBackend(contribution.definition().backend().name());
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    dto.setDescription(descriptor.description());
    return dto;
  }
}
