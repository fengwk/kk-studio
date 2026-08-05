package fun.fengwk.kkstudio.harness.runtime.processor;

/**
 * Processor 侧异常渲染工具。
 *
 * <p>异常描述（message / toString）可能被恶意或损坏的异常对象破坏（getMessage / toString 抛异常），而渲染只服务于日志——绝不允许 渲染失败 bypass
 * 已收敛的状态转换。{@link #describe} 逐级降级且绝不抛出。
 */
final class ProcessorExceptions {

  private ProcessorExceptions() {}

  /** 非抛出的异常描述：getMessage → toString → 简单类名逐级降级；{@code null} 渲染为 {@code "null"}。 */
  static String describe(Throwable failure) {
    if (failure == null) {
      return "null";
    }
    try {
      String message = failure.getMessage();
      if (message != null && !message.isBlank()) {
        return message;
      }
    } catch (RuntimeException ignored) {
      // 降级到 toString。
    }
    try {
      String rendered = failure.toString();
      if (rendered != null && !rendered.isBlank()) {
        return rendered;
      }
    } catch (RuntimeException ignored) {
      // 降级到类名。
    }
    return failure.getClass().getSimpleName();
  }
}
