package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.kkstudio.common.page.PageQuery;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

/** 将分页值对象的边界错误收口为可翻译的请求参数校验错误。 */
final class PageQueries {

  private static final String RESOURCE = "pagination";

  private PageQueries() {}

  static PageQuery create(int pageNumber, int pageSize) {
    try {
      return new PageQuery(pageNumber, pageSize);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage(), error);
    }
  }
}
