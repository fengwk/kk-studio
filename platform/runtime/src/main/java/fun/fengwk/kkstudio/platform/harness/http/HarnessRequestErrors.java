package fun.fengwk.kkstudio.platform.harness.http;

import fun.fengwk.kkstudio.common.error.DomainException;

import java.util.function.Supplier;

/** HTTP 边界映射失败的统一翻译：请求形状错误固定为 400，不泄漏内部异常类型。 */
final class HarnessRequestErrors {

  private HarnessRequestErrors() {}

  static <T> T map(Supplier<T> mapping) {
    try {
      return mapping.get();
    } catch (IllegalArgumentException error) {
      throw DomainException.validation("request", error.getMessage());
    }
  }
}
