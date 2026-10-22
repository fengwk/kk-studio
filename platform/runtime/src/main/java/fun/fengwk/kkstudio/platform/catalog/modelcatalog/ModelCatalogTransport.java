package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

import fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource;

/** 固定 HTTPS 目录下载边界；测试可替换为无网络实现。 */
public interface ModelCatalogTransport {

  String fetch(ModelCatalogSource source);
}
