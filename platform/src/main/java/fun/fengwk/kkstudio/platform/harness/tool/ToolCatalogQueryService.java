package fun.fengwk.kkstudio.platform.harness.tool;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.util.List;
import java.util.Objects;

/** 统一 {@link RuntimeToolCatalog} 的 Platform 查询边界，提供所有可用工具的列表。 */
@Service
public class ToolCatalogQueryService {

  private final RuntimeToolCatalog toolCatalog;

  public ToolCatalogQueryService(RuntimeToolCatalog toolCatalog) {
    this.toolCatalog = Objects.requireNonNull(toolCatalog, "toolCatalog");
  }

  public List<ToolCatalogEntryDTO> listTools() {
    return toolCatalog.selectableTools().stream().map(ToolCatalogQueryService::toDto).toList();
  }

  private static ToolCatalogEntryDTO toDto(ToolContribution contribution) {
    ToolDescriptor descriptor = contribution.definition().descriptor();
    ToolRequirements requirements = contribution.requirements();
    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setId(contribution.definition().id().value());
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    dto.setDescription(descriptor.description());
    dto.setEnvironmentRequired(requirements != null && requirements.environmentRequired());
    dto.setEnvironmentId(
        requirements != null && requirements.requiredEnvironmentId() != null
            ? requirements.requiredEnvironmentId().value().toString()
            : null);
    return dto;
  }
}
