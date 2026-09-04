package fun.fengwk.kkstudio.canvas;

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
    /** 文档乐观锁版本号不匹配引发的冲突。 */
    VERSION_CONFLICT,

    /** 同一命令幂等键已绑定不一致的请求。 */
    IDEMPOTENCY_CONFLICT
  }
}
