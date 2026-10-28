package fun.fengwk.kkstudio.ide.data;

/** DATA_BIND 已下发但 Daemon 未在绑定有效期内完成回连；不得复用该 ticket 或自动重试。 */
public final class IdeDataConnectTimeoutException extends IdeDataUnavailableException {

  public IdeDataConnectTimeoutException(String message) {
    super(message);
  }
}
