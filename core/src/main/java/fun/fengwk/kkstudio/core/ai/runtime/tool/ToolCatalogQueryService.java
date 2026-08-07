package fun.fengwk.kkstudio.core.ai.runtime.tool;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.util.List;

/** 离线可选的 runtime tool catalog 的 Core 边界。 */
@Service
public class ToolCatalogQueryService {

  private final ToolCatalog toolCatalog;

  public ToolCatalogQueryService(ToolCatalog toolCatalog) {
    this.toolCatalog = toolCatalog;
  }

  public List<ToolCatalogEntryDTO> listTools() {
    return toolCatalog.descriptors().stream().map(ToolCatalogQueryService::toDto).toList();
  }

  private static ToolCatalogEntryDTO toDto(ToolDescriptor descriptor) {
    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    dto.setType(descriptor.type().name());
    dto.setDescription(descriptor.description());
    return dto;
  }
}
