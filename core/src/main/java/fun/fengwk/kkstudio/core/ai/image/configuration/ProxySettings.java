package fun.fengwk.kkstudio.core.ai.image.configuration;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 解析标准代理环境变量。 */
public final class ProxySettings {

  private final ProxyTarget httpProxy;
  private final ProxyTarget httpsProxy;
  private final NoProxyRules noProxyRules;

  private ProxySettings(ProxyTarget httpProxy, ProxyTarget httpsProxy, NoProxyRules noProxyRules) {
    this.httpProxy = httpProxy;
    this.httpsProxy = httpsProxy;
    this.noProxyRules = noProxyRules;
  }

  public static ProxySettings fromEnvironment(Map<String, String> env) {
    return new ProxySettings(
        ProxyEnvironmentParser.resolveProxy(env, "http_proxy", "HTTP_PROXY"),
        ProxyEnvironmentParser.resolveProxy(env, "https_proxy", "HTTPS_PROXY"),
        NoProxyRules.fromEnvironment(env, "no_proxy", "NO_PROXY"));
  }

  public boolean isEnabled() {
    return httpProxy != null || httpsProxy != null;
  }

  public ProxySelector toProxySelector() {
    return new ProxySelector() {
      @Override
      public List<Proxy> select(URI uri) {
        if (uri == null) {
          throw new IllegalArgumentException("uri must not be null");
        }
        if (noProxyRules.matches(uri)) {
          return List.of(Proxy.NO_PROXY);
        }
        ProxyTarget proxy = proxyForScheme(uri.getScheme());
        if (proxy == null) {
          return List.of(Proxy.NO_PROXY);
        }
        return List.of(proxy.toJavaNetProxy());
      }

      @Override
      public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        // 由请求方处理失败。
      }
    };
  }

  public Authenticator toAuthenticator() {
    Map<String, PasswordAuthentication> credentialsByAuthority = new LinkedHashMap<>();
    registerCredential(credentialsByAuthority, httpProxy);
    registerCredential(credentialsByAuthority, httpsProxy);
    if (credentialsByAuthority.isEmpty()) {
      return null;
    }

    return new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        if (getRequestorType() != RequestorType.PROXY) {
          return null;
        }
        return credentialsByAuthority.get(authorityKey(getRequestingHost(), getRequestingPort()));
      }
    };
  }

  private ProxyTarget proxyForScheme(String scheme) {
    String normalizedScheme =
        ProxyEnvironmentParser.isBlank(scheme) ? "" : scheme.trim().toLowerCase(Locale.ROOT);
    return switch (normalizedScheme) {
      case "http", "ws" -> httpProxy;
      default -> httpsProxy != null ? httpsProxy : httpProxy;
    };
  }

  private static void registerCredential(
      Map<String, PasswordAuthentication> credentialsByAuthority, ProxyTarget proxy) {
    if (proxy == null || ProxyEnvironmentParser.isBlank(proxy.username())) {
      return;
    }
    credentialsByAuthority.put(
        authorityKey(proxy.host(), proxy.port()),
        new PasswordAuthentication(
            proxy.username(), (proxy.password() == null ? "" : proxy.password()).toCharArray()));
  }

  private static String authorityKey(String host, int port) {
    return (host == null ? "" : host.toLowerCase(Locale.ROOT)) + ":" + port;
  }
}
