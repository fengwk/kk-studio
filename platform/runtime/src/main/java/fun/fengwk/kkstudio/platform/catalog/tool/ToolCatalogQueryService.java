package fun.fengwk.kkstudio.platform.catalog.tool;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.util.List;
import java.util.Objects;

/**
 * 统一运行时工具目录的 Platform 查询边界，提供所有可用工具的列表。
 *
 * <p>每次查询取得一次 current 租用：列表与可用性来自同一 epoch 的冻结目录。
 */
@Service
public class ToolCatalogQueryService {

  private final RuntimeToolCatalogSource toolCatalogSource;

  public ToolCatalogQueryService(RuntimeToolCatalogSource toolCatalogSource) {
    this.toolCatalogSource = Objects.requireNonNull(toolCatalogSource, "toolCatalogSource");
  }

  public List<ToolCatalogEntryDTO> listTools() {
    try (RuntimeToolCatalogSource.Lease lease = toolCatalogSource.acquireCurrent()) {
      return lease.catalog().selectableTools().stream()
          .map(ToolCatalogQueryService::toDto)
          .toList();
    }
  }

  private static ToolCatalogEntryDTO toDto(ToolContribution contribution) {
    ToolDescriptor descriptor = contribution.definition().descriptor();
    ToolRequirements requirements = contribution.requirements();
    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setName(descriptor.name());
    dto.setDescription(descriptor.description());
    dto.setEnvironmentSupport(
        requirements == null
            ? EnvironmentSupportDTO.NONE
            : EnvironmentSupportDTO.valueOf(requirements.environmentSupport().name()));
    dto.setRequiredEnvironmentId(
        requirements != null && requirements.requiredEnvironmentId() != null
            ? requirements.requiredEnvironmentId().value().toString()
            : null);
    return dto;
  }
}
