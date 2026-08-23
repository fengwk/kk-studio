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
    NOT_FOUND,
    CONFLICT
  }
}
