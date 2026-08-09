package fun.fengwk.kkstudio.core.studio.resource;

/** Canvas Resource storage/upload 的可分类业务错误。 */
public class CanvasResourceStorageException extends RuntimeException {

  private final Reason reason;

  public CanvasResourceStorageException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    NOT_FOUND,
    EXPIRED,
    S3_MISSING
  }
}
