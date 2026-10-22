package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;
import fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO;

import java.util.List;

/** 一个外部目录版本独立实现的适配器。 */
public interface ModelsDevCatalogAdapter {

  ModelCatalogSource source();

  List<ModelDefaultCandidateDTO> adapt(String json);
}
