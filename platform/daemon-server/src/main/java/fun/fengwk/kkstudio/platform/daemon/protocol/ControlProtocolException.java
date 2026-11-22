package fun.fengwk.kkstudio.platform.daemon.protocol;

/**
 * 控制协议在成帧、解析或应用层校验边界上的确定性拒绝。
 *
 * <p><b>文案可信度不是类型不变量：</b>由 {@link ControlProtocolFields}、{@link ControlProtocolValidator}、{@link
 * ControlProtocolTransferChecks}、{@link ControlProtocolIdeChecks} 与 {@link ProtocolJson}
 * 构造的拒绝只由固定字段路径与规则短语组成，不含输入值、 远端内容或凭据；但同一类型也被 server / transport 代码用于本地状态与存储失败（如 {@code "database
 * unavailable: " + error.getMessage()}）， 其正文可能带远端或内部细节。因此调用方只能在确认异常来自上述校验器时才记录正文，其余情况只记异常类名。
 */
public final class ControlProtocolException extends RuntimeException {

  public ControlProtocolException(String message) {
    super(message);
  }

  public ControlProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
