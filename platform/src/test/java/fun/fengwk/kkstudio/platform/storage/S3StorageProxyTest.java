package fun.fengwk.kkstudio.platform.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import software.amazon.awssdk.services.s3.S3Client;

import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageConfiguration;
import fun.fengwk.kkstudio.platform.storage.configuration.S3StorageProperties;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 用真实 S3 headBucket 验证 AWS Apache 路由与系统 selector 一致，禁用不继承宿主代理。 */
@ResourceLock("jvm-proxy-selector")
class S3StorageProxyTest {

  /** S3 endpoint 不可解析，只有显式本地代理可达；同时关闭 SDK 的环境/系统属性代理回退。 */
  @Test
  void routesS3RequestsThroughTheSharedSelector() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    AtomicReference<String> host = new AtomicReference<>();
    HttpServer proxy = server(requests, host);
    try {
      ProxySelector selector =
          HttpProxySelector.fixed("http://localhost:" + proxy.getAddress().getPort(), "");
      try (S3Client client =
          new S3StorageConfiguration()
              .s3Client(properties("http://unresolvable.invalid"), selector)) {
        client.headBucket(builder -> builder.bucket("media"));
      }
      assertEquals(1, requests.get());
      assertEquals("unresolvable.invalid", host.get());
    } finally {
      proxy.stop(0);
    }
  }

  /** 配置为 DIRECT 或 bypass 时，即使系统属性指向坏代理，AWS 也必须真实直连而不是读取自己的环境策略。 */
  @Test
  void directAndBypassDoNotFallBackToSdkProxyDefaults() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    HttpServer target = server(requests, new AtomicReference<>());
    String previousHost = System.getProperty("http.proxyHost");
    String previousPort = System.getProperty("http.proxyPort");
    String previousBypass = System.getProperty("http.nonProxyHosts");
    try {
      System.setProperty("http.proxyHost", "unresolvable.invalid");
      System.setProperty("http.proxyPort", "3128");
      System.setProperty("http.nonProxyHosts", "");
      String endpoint = "http://127.0.0.1:" + target.getAddress().getPort();
      for (ProxySelector selector :
          new ProxySelector[] {
            HttpProxySelector.fixed(null, ""),
            HttpProxySelector.fixed("http://unresolvable.invalid:3128", "127.*")
          }) {
        try (S3Client client =
            new S3StorageConfiguration().s3Client(properties(endpoint), selector)) {
          client.headBucket(builder -> builder.bucket("media"));
        }
      }
      assertEquals(2, requests.get());
    } finally {
      restore("http.proxyHost", previousHost);
      restore("http.proxyPort", previousPort);
      restore("http.nonProxyHosts", previousBypass);
      target.stop(0);
    }
  }

  private static S3StorageProperties properties(String endpoint) {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setEndpoint(endpoint);
    properties.setRegion("us-east-1");
    properties.setBucket("media");
    properties.setAccessKey("test-access");
    properties.setSecretKey("test-secret");
    return properties;
  }

  private static HttpServer server(AtomicInteger requests, AtomicReference<String> host)
      throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          host.set(exchange.getRequestHeaders().getFirst("Host"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
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
}
