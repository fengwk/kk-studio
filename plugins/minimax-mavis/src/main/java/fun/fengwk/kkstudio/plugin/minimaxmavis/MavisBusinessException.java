package fun.fengwk.kkstudio.plugin.minimaxmavis;

/**
 * 服务端接受了请求但返回非零业务状态。
 *
 * <p>{@code 402} 表示额度不足，提示调用方停止生成类请求；其他状态码由调用方按上下文处理。
 */
public class MavisBusinessException extends MavisException {

  private final String context;
  private final String code;
  private final String serverMessage;

  public MavisBusinessException(String context, String code, String serverMessage) {
    super(
        context
            + " failed with code "
            + code
            + ": "
            + serverMessage
            + ("402".equals(code) ? " Do not retry until credits are available." : ""));
    this.context = context;
    this.code = code;
    this.serverMessage = serverMessage;
  }

  /** 出错的 Mavis 操作名，例如 MCP endpoint 名。 */
  public String context() {
    return context;
  }

  /** 服务端业务状态码文本。 */
  public String code() {
    return code;
  }

  /** 去敏并截断后的服务端消息。 */
  public String serverMessage() {
    return serverMessage;
  }
}
