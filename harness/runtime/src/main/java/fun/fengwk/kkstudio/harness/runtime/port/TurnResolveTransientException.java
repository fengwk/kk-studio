package fun.fengwk.kkstudio.harness.runtime.port;

/**
 * {@link TurnResolver} 的显式 typed transient infrastructure 失败：解析期间底层基础设施（数据库 / 网络）暂时不可用。
 *
 * <p>只有该类型会被 Processor 按失败延迟 reschedule（零 durable mutation）。任何其它 {@link RuntimeException}、null 结果或
 * validator 契约失败都被视为确定性失败，必须落成 durable AssistantError + FAILED TURN_END 并结算 Join，绝不无限重排。
 */
public class TurnResolveTransientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TurnResolveTransientException(String message, Throwable cause) {
    super(message, cause);
  }
}
