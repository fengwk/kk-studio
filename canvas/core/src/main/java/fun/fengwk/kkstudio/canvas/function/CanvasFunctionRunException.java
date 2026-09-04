package fun.fengwk.kkstudio.canvas.function;

/** Canvas Function run 的公开 not-found/conflict 语义。 */
public class CanvasFunctionRunException extends RuntimeException {

  private final Reason reason;

  public CanvasFunctionRunException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    /** 未找到指定的函数执行记录。 */
    NOT_FOUND,

    /** 当前执行记录与请求标识或预期状态不一致，无法安全推进。 */
    CONFLICT
  }
}
