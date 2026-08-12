package fun.fengwk.kkstudio.studio.canvas;

/** Canvas CAS 或幂等冲突。 */
public class CanvasConflictException extends RuntimeException {

  private final Reason reason;

  public CanvasConflictException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    VERSION_CONFLICT,
    IDEMPOTENCY_CONFLICT
  }
}
