package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.ai.runtime.tool.ToolCatalogQueryService;
import fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO;

import java.util.List;

/** Read-only offline-selectable runtime tool catalog. */
@AllArgsConstructor
@RequestMapping("/api/ai/catalog/tools")
@RestController
public class StudioToolCatalogController {

  private final ToolCatalogQueryService toolCatalogQueryService;

  @GetMapping
  public Result<List<ToolCatalogEntryDTO>> listTools() {
    return Results.ok(toolCatalogQueryService.listTools());
  }
}
