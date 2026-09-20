package fun.fengwk.kkstudio.plugin.minimaxmavis;

/**
 * 窄 HTTP 传输 seam。
 *
 * <p>协议客户端只依赖本接口，因此全部协议与安全边界都可以用假传输驱动，无需访问真实网络。生产实现见 {@link JdkMavisHttpTransport}：它固定
 * HTTP/1.1、禁止自动 redirect，并且不做任何重试。
 *
 * <p>实现必须在传输失败时抛出 {@link MavisTransportException}，其消息只允许包含异常类型名等无凭据信息；调用方会再对这些 消息做去敏并补上请求上下文。
 */
@FunctionalInterface
public interface MavisHttpTransport {

  /** 发送一次请求，且只发送一次。 */
  MavisHttpResponse send(MavisHttpRequest request);
}
