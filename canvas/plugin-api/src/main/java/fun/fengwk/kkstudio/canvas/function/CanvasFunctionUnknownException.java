package fun.fengwk.kkstudio.canvas.function;

/**
 * Adapter 声明「外部提交结果不明」：Runtime 会把 Run 收敛为 {@code UNKNOWN}，保留本次 pin 并退出自动调度。
 *
 * <p>只有确认无法安全查询原任务、也不存在幂等依据时才允许抛出。抛出后不得由自动恢复重新提交，必须由人工核查事实并显式解除；消息会作为公开 {@code error}
 * 展示，因此不得包含凭据或内部下载地址。
 */
public class CanvasFunctionUnknownException extends RuntimeException {

  public CanvasFunctionUnknownException(String message) {
    super(requireText(message));
  }

  private static String requireText(String message) {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("unknown reason must not be blank");
    }
    return message;
  }
}
