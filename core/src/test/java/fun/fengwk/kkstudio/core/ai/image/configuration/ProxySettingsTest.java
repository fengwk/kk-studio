package fun.fengwk.kkstudio.core.ai.image.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * @author fengwk
 */
public class ProxySettingsTest {

  @Test
  public void shouldSelectProxyAndBypassNoProxyRules() {
    ProxySettings settings =
        ProxySettings.fromEnvironment(
            Map.of(
                "http_proxy", "proxy.example:8080",
                "HTTPS_PROXY", "https://secure.example",
                "no_proxy", ".internal,10.0.0.0/8,localhost"));

    ProxySelector selector = settings.toProxySelector();

    assertTrue(settings.isEnabled());
    assertProxy(
        selector.select(URI.create("http://api.example")), Proxy.Type.HTTP, "proxy.example", 8080);
    assertProxy(
        selector.select(URI.create("https://api.example")), Proxy.Type.HTTP, "secure.example", 443);
    assertProxy(
        selector.select(URI.create("wss://api.example")), Proxy.Type.HTTP, "secure.example", 443);
    assertProxy(
        selector.select(URI.create("ftp://api.example")), Proxy.Type.HTTP, "secure.example", 443);
    assertSame(Proxy.NO_PROXY, selector.select(URI.create("http://service.internal")).get(0));
    assertSame(Proxy.NO_PROXY, selector.select(URI.create("http://localhost")).get(0));
    assertSame(Proxy.NO_PROXY, selector.select(URI.create("http://10.1.2.3")).get(0));
    assertThrows(IllegalArgumentException.class, () -> selector.select(null));
    selector.connectFailed(URI.create("http://api.example"), null, null);
  }

  @Test
  public void shouldReturnNoProxyWhenDisabledOrSchemeHasNoMatchingProxy() {
    ProxySettings disabled = ProxySettings.fromEnvironment(Map.of());
    ProxySettings httpOnly = ProxySettings.fromEnvironment(Map.of("http_proxy", "proxy.example"));

    assertFalse(disabled.isEnabled());
    assertSame(
        Proxy.NO_PROXY,
        disabled.toProxySelector().select(URI.create("https://api.example")).get(0));
    assertSame(
        Proxy.NO_PROXY, httpOnly.toProxySelector().select(URI.create("https://127.0.0.1")).get(0));
    assertProxy(
        httpOnly.toProxySelector().select(URI.create("https://api.example")),
        Proxy.Type.HTTP,
        "proxy.example",
        80);
  }

  @Test
  public void shouldRejectMalformedProxy() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProxySettings.fromEnvironment(Map.of("http_proxy", "http://:8080")));
  }

  @Test
  public void shouldCreateProxyAuthenticatorOnlyWhenCredentialsExist() throws Exception {
    ProxySettings withCredential =
        ProxySettings.fromEnvironment(
            Map.of("HTTPS_PROXY", "https://alice:secret@secure.example:8443"));
    ProxySettings withoutCredential =
        ProxySettings.fromEnvironment(Map.of("HTTPS_PROXY", "https://secure.example:8443"));

    Authenticator authenticator = withCredential.toAuthenticator();
    assertTrue(withCredential.isEnabled());
    assertTrue(authenticator != null);
    try {
      Authenticator.setDefault(authenticator);
      // 通过 JDK 代理认证回调验证凭据仅在代理请求时暴露。
      PasswordAuthentication passwordAuthentication =
          Authenticator.requestPasswordAuthentication(
              "secure.example",
              null,
              8443,
              "https",
              "",
              "",
              URI.create("https://api.example").toURL(),
              Authenticator.RequestorType.PROXY);
      assertTrue(passwordAuthentication != null);
      assertEquals("alice", passwordAuthentication.getUserName());
      assertEquals("secret", new String(passwordAuthentication.getPassword()));
    } finally {
      Authenticator.setDefault(null);
    }

    assertNull(withoutCredential.toAuthenticator());
  }

  private void assertProxy(List<Proxy> proxies, Proxy.Type type, String host, int port) {
    assertEquals(1, proxies.size());
    Proxy proxy = proxies.get(0);
    assertEquals(type, proxy.type());
    InetSocketAddress address = (InetSocketAddress) proxy.address();
    assertEquals(host, address.getHostString());
    assertEquals(port, address.getPort());
  }
}
