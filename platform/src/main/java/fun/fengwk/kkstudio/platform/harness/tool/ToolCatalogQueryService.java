package fun.fengwk.kkstudio.platform.harness.tool;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.util.List;

/** 离线可选的 runtime tool catalog 的 Platform 边界。 */
@Service
public class ToolCatalogQueryService {

  private final AgentToolRegistry toolRegistry;

  public ToolCatalogQueryService(AgentToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  public List<ToolCatalogEntryDTO> listTools() {
    return toolRegistry.selectableEntries().stream().map(ToolCatalogQueryService::toDto).toList();
  }

  private static ToolCatalogEntryDTO toDto(AgentToolRegistry.Entry entry) {
    ToolDescriptor descriptor = entry.definition().descriptor();
    ToolCatalogEntryDTO dto = new ToolCatalogEntryDTO();
    dto.setId(entry.id().value());
    dto.setBackend(entry.definition().backend().name());
    dto.setName(descriptor.name());
    dto.setVersion(descriptor.version());
    dto.setType(descriptor.type().name());
    dto.setDescription(descriptor.description());
    return dto;
  }
}
