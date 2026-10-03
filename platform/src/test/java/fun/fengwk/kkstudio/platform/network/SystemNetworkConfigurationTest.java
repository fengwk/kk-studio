package fun.fengwk.kkstudio.platform.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** 启动安装、单次读取、全局强制直连和第三方 JDK 客户端继承的真实 HTTP 证据。 */
@ResourceLock("jvm-proxy-selector")
class SystemNetworkConfigurationTest {

  /** 故意先注册客户端配置；依赖确保安装已完成。显式注入与第三方默认客户端均走同一本地代理。 */
  @Test
  void installsBeforeClientsAndFreezesTheStartupSnapshot() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer proxy = server(requests);
    ProxySelector previous = ProxySelector.getDefault();
    SystemSettingsSnapshot snapshot =
        snapshot("http://localhost:" + proxy.getAddress().getPort(), "");
    try {
      runner(snapshot)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                ProxySelector selector =
                    context.getBean("systemProxySelector", ProxySelector.class);
                assertSame(selector, ProxySelector.getDefault());
                HttpClient managed = context.getBean("managedClient", HttpClient.class);
                assertSame(selector, managed.proxy().orElseThrow());
                snapshot.replace(settings(null, ""));
                URI target = URI.create("http://unresolvable.invalid/media");
                assertEquals(Proxy.Type.HTTP, selector.select(target).getFirst().type());
                assertEquals("proxy-body", send(managed, target));
                assertEquals(
                    "proxy-body",
                    send(context.getBean("thirdPartyClient", HttpClient.class), target));
                assertEquals(2, requests.get());
              });
      assertSame(previous, ProxySelector.getDefault());
    } finally {
      ProxySelector.setDefault(previous);
      proxy.stop(0);
    }
  }

  /** 禁用代理后不得回退 JVM 属性或之前的默认 selector；两种 JDK 客户端都真实到达直连目标。 */
  @Test
  void disabledProxyExplicitlyOverridesHostDefaults() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer target = server(requests);
    ProxySelector previous = ProxySelector.getDefault();
    String previousHost = System.getProperty("http.proxyHost");
    String previousPort = System.getProperty("http.proxyPort");
    String previousBypass = System.getProperty("http.nonProxyHosts");
    try {
      System.setProperty("http.proxyHost", "unresolvable.invalid");
      System.setProperty("http.proxyPort", "3128");
      System.setProperty("http.nonProxyHosts", "");
      runner(snapshot(null, ""))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                ProxySelector selector =
                    context.getBean("systemProxySelector", ProxySelector.class);
                assertSame(selector, ProxySelector.getDefault());
                URI uri =
                    URI.create("http://127.0.0.1:" + target.getAddress().getPort() + "/media");
                assertEquals(Proxy.NO_PROXY, selector.select(uri).getFirst());
                assertEquals(
                    "proxy-body", send(context.getBean("managedClient", HttpClient.class), uri));
                assertEquals(
                    "proxy-body", send(context.getBean("thirdPartyClient", HttpClient.class), uri));
                assertEquals(2, requests.get());
              });
      assertSame(previous, ProxySelector.getDefault());
    } finally {
      restore("http.proxyHost", previousHost);
      restore("http.proxyPort", previousPort);
      restore("http.nonProxyHosts", previousBypass);
      ProxySelector.setDefault(previous);
      target.stop(0);
    }
  }

  private static ApplicationContextRunner runner(SystemSettingsSnapshot snapshot) {
    return new ApplicationContextRunner()
        .withUserConfiguration(EarlyClients.class, SystemNetworkConfiguration.class)
        .withBean(SystemSettingsSnapshot.class, () -> snapshot);
  }

  private static SystemSettingsSnapshot snapshot(String proxyUrl, String bypass) {
    return new SystemSettingsSnapshot(settings(proxyUrl, bypass));
  }

  private static SystemSettings settings(String proxyUrl, String bypass) {
    SystemSettings defaults = SystemSettings.DEFAULT;
    return new SystemSettings(
        defaults.tool(),
        defaults.aiRuntime(),
        defaults.environment(),
        new SystemSettings.Network(proxyUrl, bypass),
        defaults.integrations(),
        defaults.storageMedia(),
        defaults.advanced());
  }

  private static String send(HttpClient client, URI uri) {
    try {
      return client
          .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (Exception error) {
      throw new AssertionError("local HTTP request failed", error);
    }
  }

  private static HttpServer server(AtomicInteger requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          byte[] body = "proxy-body".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    return server;
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class EarlyClients {
    @Bean(destroyMethod = "close")
    HttpClient managedClient(@Qualifier("systemProxySelector") ProxySelector selector) {
      return HttpClient.newBuilder().proxy(selector).build();
    }

    @Bean(destroyMethod = "close")
    @DependsOn("systemProxySelector")
    HttpClient thirdPartyClient() {
      return HttpClient.newHttpClient();
    }
  }
}
