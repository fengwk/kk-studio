package fun.fengwk.kkstudio.share.runtime;

/** Harness JSON DTO 产生且可安全公开的请求格式错误。 */
public final class HarnessRequestFormatException extends IllegalArgumentException {

  public HarnessRequestFormatException(String message) {
    super(message);
  }
}
