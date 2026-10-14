package fun.fengwk.kkstudio.platform.plugin.resource;

/**
 * Plugin 资源端口不可用的确定性错误：本部署没有绑定 {@link PluginResourceGateway}，或某个具体 Resource / 远端媒体无法在契约内取得。
 *
 * <p>调用方必须把它收敛为确定性失败结果，绝不用本地路径、第三方临时 URL 或空引用伪装成功。
 */
public class PluginResourceUnavailableException extends RuntimeException {

  public PluginResourceUnavailableException(String message) {
    super(message);
  }

  public PluginResourceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
