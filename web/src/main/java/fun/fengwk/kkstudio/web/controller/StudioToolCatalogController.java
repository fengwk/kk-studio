package fun.fengwk.kkstudio.web.controller;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.tool.ToolCatalogQueryService;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

import java.util.List;

/** 只读、可离线选用的 runtime tool catalog。 */
@AllArgsConstructor
@RequestMapping("/api/tools")
@RestController
public class StudioToolCatalogController {

  private final ToolCatalogQueryService toolCatalogQueryService;

  @GetMapping
  public ApiResponse<List<ToolCatalogEntryDTO>> listTools() {
    return ApiResponse.ok(toolCatalogQueryService.listTools());
  }
}
