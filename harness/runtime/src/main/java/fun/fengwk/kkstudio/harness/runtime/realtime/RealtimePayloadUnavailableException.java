package fun.fengwk.kkstudio.harness.runtime.realtime;

/**
 * 跨节点 realtime payload 明确不可用：origin 无存活租约、payload 已过期/被驱逐、认证失败或响应无效。
 *
 * <p>该异常只表示 live overlay 无法完整交付；调用方必须触发对应 Thread 的 RESYNC，绝不能猜测或截断事件内容，也不得影响已经提交的 durable 执行结果。
 */
public class RealtimePayloadUnavailableException extends RuntimeException {

  public RealtimePayloadUnavailableException(String message) {
    super(message);
  }

  public RealtimePayloadUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
