package fun.fengwk.kkstudio.platform.catalog.modelcatalog;

/** 单个候选的目录价格无法按当前协议完整导入；只影响该候选，不中断整份目录。 */
final class CatalogPriceException extends RuntimeException {

  CatalogPriceException(String message) {
    super(message);
  }
}
