package fun.fengwk.kkstudio.platform.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 装配顺序、快照切换的实时选路，以及 JDK/Apache 客户端跨切换的真实 HTTP 证据。 */
@ResourceLock("jvm-proxy-selector")
class SystemNetworkConfigurationTest {

  private static final URI TARGET = URI.create("http://unresolvable.invalid/media");

  /**
   * 故意先注册客户端配置；依赖确保安装已完成。同一批 client 先是走 proxyA；打开并阻塞其响应流后把快照切到 proxyB， 新请求立即进 proxyB，旧流在切换后释放仍完整读到
   * proxyA 的剩余正文——证明不重建 client、旧流不受影响。
   */
  @Test
  void switchesRoutesOnSnapshotChangeWithoutInterruptingOpenStream() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger proxyARequests = new AtomicInteger();
    AtomicInteger proxyBRequests = new AtomicInteger();
    HttpServer proxyA = streamingProxy("proxy-a", "-tail", release, proxyARequests);
    HttpServer proxyB = textProxy("proxy-b", proxyBRequests);
    ProxySelector previous = ProxySelector.getDefault();
    SystemSettingsSnapshot snapshot =
        snapshot("http://127.0.0.1:" + proxyA.getAddress().getPort(), "");
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

                HttpResponse<InputStream> held =
                    managed.send(
                        HttpRequest.newBuilder(TARGET)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                InputStream body = held.body();
                assertEquals("proxy-a", new String(body.readNBytes(7), StandardCharsets.UTF_8));

                snapshot.replace(settings("http://127.0.0.1:" + proxyB.getAddress().getPort(), ""));

                assertEquals("proxy-b", send(managed, TARGET));
                assertEquals(
                    "proxy-b", send(context.getBean("thirdPartyClient", HttpClient.class), TARGET));

                release.countDown();
                assertEquals("-tail", new String(body.readAllBytes(), StandardCharsets.UTF_8));
                body.close();
                assertEquals(1, proxyARequests.get());
                assertEquals(2, proxyBRequests.get());
              });
      assertSame(previous, ProxySelector.getDefault());
    } finally {
      release.countDown();
      ProxySelector.setDefault(previous);
      proxyA.stop(0);
      proxyB.stop(0);
    }
  }

  /** 禁用代理后不得回退 JVM 属性或之前的默认 selector；两种 JDK 客户端都真实到达直连目标。 */
  @Test
  void disabledProxyExplicitlyOverridesHostDefaults() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer target = textProxy("proxy-body", requests);
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

  /** 无关注解变更后网络设置等值，必须复用同一不可变委托，不重编 bypass。 */
  @Test
  void unrelatedSettingsChangeReusesTheProxyDelegate() {
    ProxySelector previous = ProxySelector.getDefault();
    SystemSettingsSnapshot snapshot = snapshot("http://proxy.example:3128", "bypass.example");
    try {
      runner(snapshot)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                ProxySelector selector =
                    context.getBean("systemProxySelector", ProxySelector.class);
                Proxy first = selector.select(TARGET).getFirst();
                SystemSettings current = snapshot.get();
                snapshot.replace(
                    new SystemSettings(
                        current.tool(),
                        current.aiRuntime(),
                        new SystemSettings.Environment(
                            current.environment().maxResourceBytes(),
                            current.environment().heartbeatTimeoutMillis() + 1),
                        current.network(),
                        current.integrations(),
                        current.storageMedia(),
                        current.advanced()));
                assertSame(first, selector.select(TARGET).getFirst());
              });
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /** Apache S3 使用的 SystemDefaultRoutePlanner 每次定路由现读当前网络设置，切换后走新代理。 */
  @Test
  void apacheRoutePlannerConsultsCurrentNetworkOnEveryRoute() {
    ProxySelector previous = ProxySelector.getDefault();
    SystemSettingsSnapshot snapshot = snapshot("http://proxy-a.example:3128", "");
    try {
      runner(snapshot)
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                ProxySelector selector =
                    context.getBean("systemProxySelector", ProxySelector.class);
                SystemDefaultRoutePlanner planner = new SystemDefaultRoutePlanner(selector);
                HttpHost target = new HttpHost("remote.invalid", 80, "http");
                HttpRoute first =
                    planner.determineRoute(
                        target, new BasicHttpRequest("GET", "/"), new BasicHttpContext());
                assertEquals("proxy-a.example", first.getProxyHost().getHostName());
                snapshot.replace(settings("http://proxy-b.example:3128", ""));
                HttpRoute second =
                    planner.determineRoute(
                        target, new BasicHttpRequest("GET", "/"), new BasicHttpContext());
                assertEquals("proxy-b.example", second.getProxyHost().getHostName());
              });
    } finally {
      ProxySelector.setDefault(previous);
    }
  }

  /** 意图：Backend 装配接受 CIDR 绕过；内网数值 IP 明确 DIRECT，外部目标走配置代理。 */
  @Test
  void cidrBypassSelectsDirectForInternalNumericIps() {
    ProxySelector previous = ProxySelector.getDefault();
    try {
      runner(snapshot("http://127.0.0.1:9999", "192.168.0.0/16,100.64.0.0/10,::1"))
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                ProxySelector selector =
                    context.getBean("systemProxySelector", ProxySelector.class);
                assertEquals(
                    Proxy.NO_PROXY, selector.select(URI.create("http://192.168.10.1")).getFirst());
                assertEquals(
                    Proxy.NO_PROXY, selector.select(URI.create("https://100.100.0.1")).getFirst());
                assertEquals(
                    Proxy.NO_PROXY, selector.select(URI.create("http://[::1]")).getFirst());
                assertEquals(
                    Proxy.Type.HTTP,
                    selector.select(URI.create("http://203.0.113.1")).getFirst().type());
              });
    } finally {
      ProxySelector.setDefault(previous);
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
          .send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (Exception error) {
      throw new AssertionError("local HTTP request failed", error);
    }
  }

  /** 完整正文代理：用于新请求走新代理的断言。 */
  private static HttpServer textProxy(String body, AtomicInteger requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          byte[] content = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, content.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
          }
        });
    server.start();
    return server;
  }

  /** 流式代理：先交付 head 并阻塞到 release，再写 tail，让调用方在切换快照期间持有一个未读完的响应流。 */
  private static HttpServer streamingProxy(
      String head, String tail, CountDownLatch release, AtomicInteger requests) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(head.getBytes(StandardCharsets.UTF_8));
            output.flush();
            release.await(10, TimeUnit.SECONDS);
            output.write(tail.getBytes(StandardCharsets.UTF_8));
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
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
