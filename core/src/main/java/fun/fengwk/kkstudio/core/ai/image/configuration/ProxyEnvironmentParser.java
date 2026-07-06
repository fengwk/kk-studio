package fun.fengwk.kkstudio.core.ai.image.configuration;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** ProxyEnvironmentParser 负责解析标准代理环境变量中的 proxy target。 */
final class ProxyEnvironmentParser {

  private ProxyEnvironmentParser() {}

  static ProxyTarget resolveProxy(
      Map<String, String> env, String lowerEnvName, String upperEnvName) {
    String rawValue = firstNonBlank(env.get(lowerEnvName), env.get(upperEnvName));
    if (isBlank(rawValue)) {
      return null;
    }

    String normalized = normalizeProxyUri(rawValue.trim());
    URI uri = URI.create(normalized);
    String scheme = isBlank(uri.getScheme()) ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost();
    if (isBlank(host)) {
      throw new IllegalArgumentException("Proxy host is missing: " + rawValue);
    }

    int port = uri.getPort() > 0 ? uri.getPort() : defaultPort(scheme);
    String username = null;
    String password = null;
    String userInfo = uri.getUserInfo();
    if (!isBlank(userInfo)) {
      int separator = userInfo.indexOf(':');
      if (separator >= 0) {
        username = userInfo.substring(0, separator);
        password = userInfo.substring(separator + 1);
      } else {
        username = userInfo;
      }
    }

    return new ProxyTarget(scheme, host, port, username, password);
  }

  static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (!isBlank(value)) {
        return value;
      }
    }
    return null;
  }

  static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String normalizeProxyUri(String rawValue) {
    return rawValue.contains("://") ? rawValue : "http://" + rawValue;
  }

  private static int defaultPort(String scheme) {
    return switch (scheme) {
      case "https" -> 443;
      case "socks", "socks5" -> 1080;
      default -> 80;
    };
  }
}
