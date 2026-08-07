package fun.fengwk.kkstudio.harness.runtime;

/**
 * Harness control/query 请求所引用的 durable 实体不存在（Thread、Entry target）。业务不适用状态为冲突 （{@link
 * HarnessRuntimeConflictException}），不属于 not-found。
 */
public final class HarnessRuntimeNotFoundException extends RuntimeException {

  public HarnessRuntimeNotFoundException(String message) {
    super(message);
  }
}
