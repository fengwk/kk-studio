package fun.fengwk.kkstudio.platform.catalog.http;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.dto.ToolCatalogEntryDTO;
import fun.fengwk.kkstudio.platform.catalog.tool.ToolCatalogQueryService;

import java.util.List;

/** 只读、可离线选用的 runtime tool catalog。 */
@AllArgsConstructor
@RequestMapping("/api/platform/tools")
@RestController
public class ToolCatalogController {

  private final ToolCatalogQueryService toolCatalogQueryService;

  @GetMapping
  public List<ToolCatalogEntryDTO> listTools() {
    return toolCatalogQueryService.listTools();
  }
}
