package fun.fengwk.kkstudio.ide.data;

/** 当前节点无法为该 Environment 建立或使用数据连接；调用方应 fail-closed，不自动重放逻辑请求。 */
public class IdeDataUnavailableException extends RuntimeException {

  public IdeDataUnavailableException(String message) {
    super(message);
  }

  public IdeDataUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
