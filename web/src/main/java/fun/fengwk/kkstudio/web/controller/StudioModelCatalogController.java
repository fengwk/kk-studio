package fun.fengwk.kkstudio.web.controller;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogService;
import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogPageDTO;
import fun.fengwk.kkstudio.web.api.ApiResponse;

/** 外部模型目录候选 API；不提供“选候选即保存模型”。 */
@AllArgsConstructor
@RequestMapping("/api/model-defaults")
@RestController
public class StudioModelCatalogController {

  private final ModelCatalogService modelCatalogService;

  @GetMapping
  public ApiResponse<ModelCatalogPageDTO> page(
      @RequestParam(value = "query", required = false) String query,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
    return ApiResponse.ok(modelCatalogService.page(query, cursor, pageSize));
  }

  @PostMapping("/refresh")
  public ApiResponse<ModelCatalogPageDTO> refresh() {
    return ApiResponse.ok(modelCatalogService.refresh());
  }
}
