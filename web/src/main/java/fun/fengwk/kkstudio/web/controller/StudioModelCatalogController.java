package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogService;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogPageDTO;

/** 外部模型目录候选 API；不提供“选候选即保存模型”。 */
@AllArgsConstructor
@RequestMapping("/api/ai/catalog/model-defaults")
@RestController
public class StudioModelCatalogController {

  private final ModelCatalogService modelCatalogService;

  @GetMapping
  public Result<ModelCatalogPageDTO> page(
      @RequestParam(value = "query", required = false) String query,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return Results.ok(modelCatalogService.page(query, cursor, pageSize));
  }

  @PostMapping("/refresh")
  public Result<ModelCatalogPageDTO> refresh() {
    return Results.ok(modelCatalogService.refresh());
  }
}
