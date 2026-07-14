package fun.fengwk.kkstudio.harness.runtime.tool;

/** interceptor 异常的明确边界，保留阶段和实现类。 */
public class ToolInterceptorException extends RuntimeException {
  public ToolInterceptorException(String phase, Object interceptor, Throwable cause) {
    super(
        phase + " interceptor failed: " + interceptor.getClass().getName() + ": " + message(cause),
        cause);
  }

  private static String message(Throwable cause) {
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }
}
