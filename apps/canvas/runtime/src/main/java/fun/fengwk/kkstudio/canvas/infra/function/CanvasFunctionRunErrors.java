package fun.fengwk.kkstudio.canvas.infra.function;

import fun.fengwk.kkstudio.common.error.DomainException;

/** FunctionRun 短事务对外返回的两类领域错误。 */
final class CanvasFunctionRunErrors {

  private CanvasFunctionRunErrors() {}

  static DomainException notFound(String message) {
    return DomainException.notFound("canvas_function_run", message);
  }

  static DomainException conflict(String message) {
    return DomainException.conflict("FUNCTION_RUN_CONFLICT", message);
  }
}
