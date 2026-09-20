package fun.fengwk.kkstudio.plugin.minimaxmavis;

/** 本地输入在发出任何 Mavis 请求之前即被拒绝。 */
public class MavisValidationException extends MavisException {

  public MavisValidationException(String message) {
    super(message);
  }

  public MavisValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
