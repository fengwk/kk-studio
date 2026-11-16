package fun.fengwk.kkstudio.plugin.minimaxmavis;

/**
 * 服务端接受了请求但返回非零业务状态。
 *
 * <p>{@code 402} 表示额度不足，提示调用方停止生成类请求；其他状态码由调用方按上下文处理。
 */
public class MavisBusinessException extends MavisException {

  public MavisBusinessException(String context, String code, String serverMessage) {
    super(
        context
            + " failed with code "
            + code
            + ": "
            + serverMessage
            + ("402".equals(code) ? " Do not retry until credits are available." : ""));
  }
}
