package fun.fengwk.kkstudio.canvas;

/** Canvas 写入准入失败：目标画布不存在，或同一幂等键被不一致的请求复用。 */
public class CanvasConflictException extends RuntimeException {

  private final Reason reason;

  public CanvasConflictException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  public CanvasConflictException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  /** 准入失败原因。 */
  public enum Reason {
    /** 目标画布不存在。 */
    CANVAS_NOT_FOUND,

    /** 同一命令幂等键已绑定不一致的请求指纹。 */
    IDEMPOTENCY_CONFLICT
  }
}
