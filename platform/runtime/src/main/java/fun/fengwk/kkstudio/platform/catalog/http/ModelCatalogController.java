package fun.fengwk.kkstudio.platform.catalog.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogPageDTO;
import fun.fengwk.kkstudio.platform.catalog.modelcatalog.ModelCatalogService;

/** 外部模型目录候选 API；不提供“选候选即保存模型”。 */
@RestController
@RequestMapping("/api/platform/model-catalog")
public class ModelCatalogController {

  private final ModelCatalogService modelCatalogService;

  public ModelCatalogController(ModelCatalogService modelCatalogService) {
    this.modelCatalogService = modelCatalogService;
  }

  @GetMapping
  public ModelCatalogPageDTO page(
      @RequestParam(value = "query", required = false) String query,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return modelCatalogService.page(query, cursor, limit);
  }

  @PostMapping("/refresh")
  public ModelCatalogPageDTO refresh() {
    return modelCatalogService.refresh();
  }
}
