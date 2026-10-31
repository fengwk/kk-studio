package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

/** 固定 HTTPS 目录下载边界；测试可替换为无网络实现。 */
public interface ModelCatalogTransport {

  String fetch();
}
