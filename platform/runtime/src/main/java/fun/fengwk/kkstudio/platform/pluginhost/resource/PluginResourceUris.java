package fun.fengwk.kkstudio.platform.pluginhost.resource;

import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceUnavailableException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Plugin 资源端口的 URL 形状规则：它们都不信任调用方给出的字符串或响应头，只接受没有敏感成分的绝对地址。
 *
 * <p>下载入口要求 HTTPS 且没有 userinfo/fragment；宿主签发的会话资源地址同样必须是无 userinfo 的 HTTPS；预签名上传目标只要求绝对
 * URI，userinfo 由调用点单独拒绝。
 */
final class PluginResourceUris {

  private PluginResourceUris() {}

  /** 下载地址必须是没有敏感成分的绝对 HTTPS URI。 */
  static URI requireDownloadable(URI remoteUri) {
    if (remoteUri == null || !remoteUri.isAbsolute()) {
      throw new PluginResourceUnavailableException("remote media uri must be absolute");
    }
    if (!"https".equalsIgnoreCase(remoteUri.getScheme())) {
      throw new PluginResourceUnavailableException("remote media uri must use https");
    }
    if (remoteUri.getHost() == null
        || remoteUri.getFragment() != null
        || remoteUri.getUserInfo() != null) {
      throw new PluginResourceUnavailableException(
          "remote media uri must not carry userinfo, fragment or an empty host");
    }
    return remoteUri;
  }

  /** 宿主签发的会话资源地址：必须是可用的 HTTPS 地址且不携带 userinfo。 */
  static URI toHttpsUri(String value) {
    URI uri = parseAbsoluteUri(value);
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new PluginResourceUnavailableException("signed session resource url is not usable");
    }
    return uri;
  }

  static URI parseAbsoluteUri(String value) {
    if (value == null || value.isBlank()) {
      throw new PluginResourceUnavailableException("required url is missing");
    }
    try {
      URI uri = new URI(value);
      if (!uri.isAbsolute()) {
        throw new PluginResourceUnavailableException("required url must be absolute");
      }
      return uri;
    } catch (URISyntaxException error) {
      throw new PluginResourceUnavailableException("required url is malformed", error);
    }
  }
}
