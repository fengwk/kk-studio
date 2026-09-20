package fun.fengwk.kkstudio.plugin.minimaxmavis;

/**
 * MiniMax Mavis 凭据缺失、过期、被拒绝或无法完成认证。
 *
 * <p>该错误是确定性认证拒绝：调用方必须重新登录，不能重放原请求。
 */
public class MavisAuthException extends MavisException {

  /** 认证被拒绝或过期时的统一提示；不含任何凭据，可安全进入日志与响应。 */
  public static final String REJECTED_MESSAGE =
      "MiniMax Mavis authentication was rejected or expired; re-authenticate the minimax-mavis plugin";

  public MavisAuthException(String message) {
    super(message);
  }

  public MavisAuthException(String message, Throwable cause) {
    super(message, cause);
  }
}
