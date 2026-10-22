package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

/** 下载失败或响应不符合固定外部边界。 */
public final class ModelCatalogFetchException extends RuntimeException {

  ModelCatalogFetchException(String message) {
    super(message);
  }
}
