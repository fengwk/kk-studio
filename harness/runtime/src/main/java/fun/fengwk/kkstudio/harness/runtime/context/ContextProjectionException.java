package fun.fengwk.kkstudio.harness.runtime.context;

/** 当前活动链无法投影为可执行 Context。 */
public class ContextProjectionException extends RuntimeException {
  public ContextProjectionException(String message) {
    super(message);
  }
}
