package fun.fengwk.kkstudio.platform.plugin.resource;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 远端媒体下载与预签名直传的窄传输端口。
 *
 * <p>把它独立出来是为了让「地址准入、重定向禁止、字节与期限预算、摘要与嗅探」这些安全规则可以被确定性测试，而不用真的连网：实现负责 HTTP 传输细节， 网关负责所有校验与状态收敛。
 */
interface RemoteMediaTransport {

  /** GET 远端媒体的响应头与响应体（调用方负责关闭响应体）。 */
  record MediaResponse(int status, Map<String, List<String>> headers, InputStream body) {}

  /**
   * 发起一次 GET：实现必须禁止自动重定向（3xx 必须作为普通响应返回给调用方判定），并使用给定的整体期限。
   *
   * @throws PluginResourceUnavailableException 连接失败、超时或响应无法读取
   */
  MediaResponse get(URI uri, Duration timeout);

  /**
   * 发起一次预签名 PUT：把本地文件作为请求体流式上传，并原样带上调用方给出的已签名头（restricted header 已由调用方过滤）。
   *
   * @return HTTP 状态码；2xx 之外由调用方判定失败
   * @throws PluginResourceUnavailableException 连接失败、超时或上传无法完成
   */
  int put(URI uri, Map<String, String> headers, Path file, Duration timeout);

  /** 上传用不到的、由 HTTP 客户端自己管理的受限头：手工设置会被 JDK 拒绝。 */
  static boolean isRestrictedHeader(String name) {
    return "host".equalsIgnoreCase(name)
        || "content-length".equalsIgnoreCase(name)
        || "connection".equalsIgnoreCase(name)
        || "expect".equalsIgnoreCase(name)
        || "upgrade".equalsIgnoreCase(name)
        || "via".equalsIgnoreCase(name)
        || "transfer-encoding".equalsIgnoreCase(name);
  }
}
