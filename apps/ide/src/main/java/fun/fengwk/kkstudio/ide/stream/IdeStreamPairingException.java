package fun.fengwk.kkstudio.ide.stream;

/**
 * 反向数据面配对的失败：等待 Daemon 反向连接超时、配对在交付前被关闭（取消/代际结束），或等待被中断。
 *
 * <p>适配层据此关闭浏览器侧（如 504/取消）并放弃在途 {@code OPEN_STREAM} 命令；不重试、不伪造数据。
 */
public class IdeStreamPairingException extends RuntimeException {

  public IdeStreamPairingException(String message) {
    super(message);
  }

  public IdeStreamPairingException(String message, Throwable cause) {
    super(message, cause);
  }
}
