package fun.fengwk.kkstudio.platform.plugin.resource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link HttpRemoteMediaTransport} 的传输行为测试：用真实本地 HTTP/HTTPS 服务器覆盖地址 pinning、TLS 主机名校验、重定向禁止与预签名上传。
 *
 * <p>生产地址准入只放行公网地址，因此这里注入「只额外放行回环地址」的测试策略：测试服务器可达，但 DNS rebinding 与私网地址仍按生产规则被拒绝。
 */
class HttpRemoteMediaTransportTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** 单纯用于校验字节透传的最小 PNG 头。 */
  private static final byte[] PNG = {
    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D
  };

  private static final String MEDIA_HOST = "media.example.com";

  /** 本机回环地址；测试服务器只监听它。 */
  private static final String LOOPBACK = "127.0.0.1";

  @TempDir Path tempDir;

  // =========================================================================
  // 1. DNS pinning 与 TLS
  // =========================================================================

  /** 校验地址与实际连接一致：解析只发生一次，返回的回环地址就是真正建连的地址；TLS 仍按原始主机名做 SNI 与证书校验。 */
  @Test
  void getPinsTheValidatedAddressWhileKeepingTheTlsHostName() throws Exception {
    SSLContext tls = createTestTlsContext();
    List<String> requestedServerNames = new ArrayList<>();
    Endpoint endpoint = new Endpoint();
    HttpsServer server = startHttpsServer(tls, requestedServerNames, mediaHandler(endpoint));
    SequencedHostResolver resolver = new SequencedHostResolver();
    InetAddress loopback = InetAddress.getByName(LOOPBACK);
    resolver.enqueue(MEDIA_HOST, List.of(loopback));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(loopbackPolicy(resolver)),
            new DefaultClientTlsStrategy(tls));
    try {
      URI uri =
          URI.create("https://" + MEDIA_HOST + ":" + server.getAddress().getPort() + "/media");

      RemoteMediaTransport.MediaResponse response = transport.get(uri, TIMEOUT);

      assertEquals(200, response.status());
      assertArrayEquals(PNG, readAll(response.body()));
      assertEquals(1, endpoint.requests.get());
      // TLS 使用原始主机名：SNI 是媒体域名，而实际连接地址是回环地址
      assertEquals(List.of(MEDIA_HOST), List.copyOf(requestedServerNames));
      // 客户端只解析一次，校验通过的地址就是建连使用的地址
      assertEquals(1, resolver.callCount());
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  /**
   * DNS rebinding 回归：同一个客户端的每次 socket 解析都必须重新过地址准入。第一次解析到回环地址可以连上；随后解析被改到链路本地地址时，
   * 必须在建连之前确定性失败，服务器不得收到第二次请求。
   */
  @Test
  void getRejectsAddressesResolvedAfterAPublicAnswer() throws Exception {
    Endpoint endpoint = new Endpoint();
    HttpServer server = startPlainServer(mediaHandler(endpoint));
    SequencedHostResolver resolver = new SequencedHostResolver();
    resolver.enqueue(MEDIA_HOST, List.of(InetAddress.getByName(LOOPBACK)));
    resolver.enqueue(MEDIA_HOST, List.of(InetAddress.getByName("169.254.169.254")));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT, new PublicAddressDnsResolver(loopbackPolicy(resolver)));
    try {
      URI uri = URI.create("http://" + MEDIA_HOST + ":" + server.getAddress().getPort() + "/media");

      RemoteMediaTransport.MediaResponse response = transport.get(uri, TIMEOUT);
      assertArrayEquals(PNG, readAll(response.body()));
      assertEquals(1, endpoint.requests.get());

      PluginResourceUnavailableException failure =
          assertThrows(PluginResourceUnavailableException.class, () -> transport.get(uri, TIMEOUT));

      assertTrue(failure.getMessage().contains("non-public address"));
      // 第二次解析指向链路本地地址，连接不得建立
      assertEquals(2, resolver.callCount());
      assertEquals(1, endpoint.requests.get());
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  /** 生产策略拒绝私网地址：解析到 10.0.0.1 时必须在发起任何连接之前失败。 */
  @Test
  void getRejectsNonPublicAddressBeforeConnecting() throws IOException, UnknownHostException {
    SequencedHostResolver resolver = new SequencedHostResolver();
    resolver.enqueue("private.example.com", List.of(InetAddress.getByName("10.0.0.1")));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT, new PublicAddressDnsResolver(new PublicAddressPolicy(resolver)));
    try {
      PluginResourceUnavailableException failure =
          assertThrows(
              PluginResourceUnavailableException.class,
              () -> transport.get(URI.create("https://private.example.com/media"), TIMEOUT));

      assertTrue(failure.getMessage().contains("non-public address"));
    } finally {
      transport.close();
    }
  }

  /**
   * URL 里直接写 IPv4 字面量私网地址时同样必须过地址准入：HttpClient5 从请求 URI 推导出的 {@code HttpHost} 不带已解析地址 （{@code
   * getAddress()} 为 null），字面量也会走注入的 {@link PublicAddressDnsResolver}。这里服务器真实可达， 一旦字面量被绕过准入就会拿到
   * 200，因此同时断言服务器没有收到请求。
   */
  @Test
  void getRejectsLoopbackIpv4LiteralBeforeConnecting() throws Exception {
    Endpoint endpoint = new Endpoint();
    HttpServer server = startPlainServer(mediaHandler(endpoint));
    HttpRemoteMediaTransport transport = productionTransport();
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/media");

      PluginResourceUnavailableException failure =
          assertThrows(PluginResourceUnavailableException.class, () -> transport.get(uri, TIMEOUT));

      assertTrue(failure.getMessage().contains("non-public address"));
      assertEquals(0, endpoint.requests.get());
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  /** IPv6 字面量同理：服务器真实监听 {@code ::1}，绕过准入就会真的连上，因此同样断言服务器没有收到请求。 */
  @Test
  void getRejectsIpv6LoopbackLiteralBeforeConnecting() throws Exception {
    InetAddress ipv6Loopback = ipv6LoopbackOrNull();
    Assumptions.assumeTrue(ipv6Loopback != null, "IPv6 loopback unavailable");
    Endpoint endpoint = new Endpoint();
    HttpServer server = startPlainServer(ipv6Loopback, mediaHandler(endpoint));
    HttpRemoteMediaTransport transport = productionTransport();
    try {
      URI uri = URI.create("http://[::1]:" + server.getAddress().getPort() + "/media");

      PluginResourceUnavailableException failure =
          assertThrows(PluginResourceUnavailableException.class, () -> transport.get(uri, TIMEOUT));

      assertTrue(failure.getMessage().contains("non-public address"));
      assertEquals(0, endpoint.requests.get());
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  /** ULA（fc00::/7）是 JDK 不判定为 site-local 的私网范围，字面量同样不得因「已经是字面量」而跳过判定。 */
  @Test
  void getRejectsUniqueLocalIpv6LiteralBeforeConnecting() throws IOException {
    HttpRemoteMediaTransport transport = productionTransport();
    try {
      PluginResourceUnavailableException failure =
          assertThrows(
              PluginResourceUnavailableException.class,
              () -> transport.get(URI.create("http://[fd00::1]:8443/media"), TIMEOUT));

      assertTrue(failure.getMessage().contains("non-public address"));
    } finally {
      transport.close();
    }
  }

  /** DNS 完全无法解析时必须收敛为确定性失败，而不是连接错误。 */
  @Test
  void getConvergesUnresolvableHostToUnavailable() throws IOException {
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT, new PublicAddressDnsResolver(loopbackPolicy(new SequencedHostResolver())));
    try {
      PluginResourceUnavailableException failure =
          assertThrows(
              PluginResourceUnavailableException.class,
              () -> transport.get(URI.create("https://unresolvable.example.com/media"), TIMEOUT));

      assertTrue(failure.getMessage().contains("cannot be resolved"));
    } finally {
      transport.close();
    }
  }

  /** 生产代码不得关闭 TLS 主机名校验：证书只签发给 {@code media.example.com}，用同一台本地服务器的 {@code localhost} 访问必须握手失败。 */
  @Test
  void getKeepsTlsHostnameVerification() throws Exception {
    SSLContext tls = createTestTlsContext();
    Endpoint endpoint = new Endpoint();
    HttpsServer server = startHttpsServer(tls, new ArrayList<>(), mediaHandler(endpoint));
    SequencedHostResolver resolver = new SequencedHostResolver();
    resolver.enqueue("localhost", List.of(InetAddress.getByName(LOOPBACK)));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(loopbackPolicy(resolver)),
            new DefaultClientTlsStrategy(tls));
    try {
      URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/media");

      PluginResourceUnavailableException failure =
          assertThrows(PluginResourceUnavailableException.class, () -> transport.get(uri, TIMEOUT));

      assertInstanceOf(SSLException.class, failure.getCause());
      assertEquals(0, endpoint.requests.get());
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  // =========================================================================
  // 2. 传输与失败语义
  // =========================================================================

  /** 3xx 必须作为普通响应返回：禁止自动跟随，否则一次跳转就绕过地址准入。 */
  @Test
  void getDoesNotFollowRedirects() throws Exception {
    AtomicInteger targetRequests = new AtomicInteger();
    HttpServer target =
        startPlainServer(
            exchange -> {
              targetRequests.incrementAndGet();
              respond(exchange, 200, PNG, null);
            });
    Endpoint endpoint = new Endpoint();
    HttpServer redirector =
        startPlainServer(
            exchange -> {
              endpoint.record(exchange);
              respond(
                  exchange,
                  302,
                  new byte[0],
                  "http://" + LOOPBACK + ":" + target.getAddress().getPort() + "/media");
            });
    SequencedHostResolver resolver = new SequencedHostResolver();
    resolver.enqueue(MEDIA_HOST, List.of(InetAddress.getByName(LOOPBACK)));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT, new PublicAddressDnsResolver(loopbackPolicy(resolver)));
    try {
      URI uri =
          URI.create("http://" + MEDIA_HOST + ":" + redirector.getAddress().getPort() + "/media");

      RemoteMediaTransport.MediaResponse response = transport.get(uri, TIMEOUT);

      assertEquals(302, response.status());
      assertEquals(
          "http://" + LOOPBACK + ":" + target.getAddress().getPort() + "/media",
          firstHeader(response.headers(), "location"));
      readAll(response.body());
      assertEquals(1, endpoint.requests.get());
      assertEquals(0, targetRequests.get());
      assertEquals(1, resolver.callCount());
    } finally {
      transport.close();
      redirector.stop(0);
      target.stop(0);
    }
  }

  /** 连接被拒绝等 I/O 失败必须收敛为确定性失败。 */
  @Test
  void getConvergesConnectFailureToUnavailable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
      closedPort = socket.getLocalPort();
    }
    SequencedHostResolver resolver = new SequencedHostResolver();
    resolver.enqueue(MEDIA_HOST, List.of(InetAddress.getByName(LOOPBACK)));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT, new PublicAddressDnsResolver(loopbackPolicy(resolver)));
    try {
      URI uri = URI.create("http://" + MEDIA_HOST + ":" + closedPort + "/media");

      PluginResourceUnavailableException failure =
          assertThrows(PluginResourceUnavailableException.class, () -> transport.get(uri, TIMEOUT));

      assertTrue(failure.getMessage().contains("remote media request failed"));
    } finally {
      transport.close();
    }
  }

  /** 预签名 PUT 直传可信地址：允许内网/回环目标，流式写出文件字节并转发已签名头，受限头由客户端自己决定。 */
  @Test
  void putStreamsTheStagedFileToThePresignedTarget() throws Exception {
    byte[] payload = "staged-media-bytes".getBytes(StandardCharsets.UTF_8);
    Endpoint endpoint = new Endpoint();
    HttpServer server =
        startPlainServer(
            exchange -> {
              endpoint.record(exchange);
              respond(exchange, 200, new byte[0], null);
            });
    Path file = tempDir.resolve("payload.bin");
    Files.write(file, payload);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("x-amz-signature", "signed-value");
    headers.put("Host", "attacker.example.com");
    headers.put("Content-Length", "1");
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    try {
      URI uri = URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + "/upload");

      int status = transport.put(uri, headers, file, TIMEOUT);

      assertEquals(200, status);
      assertArrayEquals(payload, endpoint.body);
      assertEquals("signed-value", endpoint.headers.getFirst("x-amz-signature"));
      // 受限头不转发：Host 由客户端按真实目标决定，Content-Length 由客户端按文件长度决定
      assertEquals(
          LOOPBACK + ":" + server.getAddress().getPort(), endpoint.headers.getFirst("Host"));
      assertEquals(String.valueOf(payload.length), endpoint.headers.getFirst("Content-Length"));
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  /** 预签名 PUT 的非 2xx 状态必须原样交回调用方判定。 */
  @Test
  void putReturnsTheTargetStatus() throws Exception {
    Endpoint endpoint = new Endpoint();
    HttpServer server =
        startPlainServer(
            exchange -> {
              endpoint.record(exchange);
              respond(exchange, 503, new byte[0], null);
            });
    Path file = tempDir.resolve("payload.bin");
    Files.write(file, new byte[] {1, 2, 3});
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    try {
      URI uri = URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + "/upload");

      assertEquals(503, transport.put(uri, Map.of(), file, TIMEOUT));
    } finally {
      transport.close();
      server.stop(0);
    }
  }

  /** 暂存文件在 PUT 之前消失时必须收敛为确定性失败，不发起上传。 */
  @Test
  void putConvergesMissingStagedFileToUnavailable() throws IOException {
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    try {
      PluginResourceUnavailableException failure =
          assertThrows(
              PluginResourceUnavailableException.class,
              () ->
                  transport.put(
                      URI.create("http://" + LOOPBACK + ":1/upload"),
                      Map.of(),
                      tempDir.resolve("absent.bin"),
                      TIMEOUT));

      assertTrue(failure.getMessage().contains("temp file disappeared"));
    } finally {
      transport.close();
    }
  }

  /** 预签名 PUT 的 I/O 失败（连接被拒绝）必须收敛为确定性失败。 */
  @Test
  void putConvergesUploadFailureToUnavailable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
      closedPort = socket.getLocalPort();
    }
    Path file = tempDir.resolve("payload.bin");
    Files.write(file, new byte[] {1, 2, 3});
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    try {
      URI uri = URI.create("http://" + LOOPBACK + ":" + closedPort + "/upload");

      PluginResourceUnavailableException failure =
          assertThrows(
              PluginResourceUnavailableException.class,
              () -> transport.put(uri, Map.of(), file, TIMEOUT));

      assertTrue(failure.getMessage().contains("presigned media upload failed"));
    } finally {
      transport.close();
    }
  }

  /** 预签名 PUT 被中断时必须收敛为确定性失败，并把中断标记交回调用线程。 */
  @Test
  void putConvergesInterruptionToUnavailable() throws Exception {
    CountDownLatch bodyConsumed = new CountDownLatch(1);
    CountDownLatch releaseResponse = new CountDownLatch(1);
    HttpServer server =
        startPlainServer(
            exchange -> {
              readAll(exchange.getRequestBody());
              bodyConsumed.countDown();
              // 请求体已经被读到，但响应迟迟不发：此刻只能靠中断脱离阻塞。
              awaitQuietly(releaseResponse);
              respond(exchange, 200, new byte[0], null);
            });
    Path file = tempDir.resolve("payload.bin");
    Files.write(file, new byte[] {1, 2, 3});
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    Thread testThread = Thread.currentThread();
    Thread interrupter =
        new Thread(
            () -> {
              awaitQuietly(bodyConsumed);
              testThread.interrupt();
            });
    try {
      interrupter.start();
      URI uri = URI.create("http://" + LOOPBACK + ":" + server.getAddress().getPort() + "/upload");

      PluginResourceUnavailableException failure =
          assertThrows(
              PluginResourceUnavailableException.class,
              () -> transport.put(uri, Map.of(), file, TIMEOUT));

      assertTrue(failure.getMessage().contains("interrupted"));
    } finally {
      releaseResponse.countDown();
      interrupter.join();
      server.stop(0);
      transport.close();
    }
    // 中断标记必须被恢复：这里读出来并清掉，避免污染后续共用该线程的测试。
    assertTrue(Thread.interrupted());
  }

  /** 关闭传输后下载客户端不可再用，资源不会被重复占用。 */
  @Test
  void closeReleasesTheDownloadClient() throws IOException {
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    transport.close();

    assertThrows(
        PluginResourceUnavailableException.class,
        () -> transport.get(URI.create("http://" + MEDIA_HOST + "/media"), TIMEOUT));
  }

  /** 关闭必须幂等（下载与上传两个客户端都不得因重复关闭而失败）：组合根销毁 bean 时可能重复关闭。 */
  @Test
  void closeIsIdempotent() throws IOException {
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT,
            new PublicAddressDnsResolver(new PublicAddressPolicy(new SequencedHostResolver())));
    transport.close();

    assertDoesNotThrow(transport::close);
  }

  /**
   * 关闭响应体必须立刻中止仍在阻塞的读取：网关的流式复制看门狗在超过整体期限后只做「关闭响应体 + 中断读线程」， 客户端自身的 responseTimeout
   * 只是空闲超时，不能代替该总体期限（这里的服务器会一直停滞到测试自己放行为止）。
   */
  @Test
  void closingTheBodyAbortsAStalledRead() throws Exception {
    CountDownLatch bodyStalled = new CountDownLatch(1);
    CountDownLatch releaseBody = new CountDownLatch(1);
    HttpServer server =
        startPlainServer(
            exchange -> {
              exchange.sendResponseHeaders(200, PNG.length * 8);
              OutputStream output = exchange.getResponseBody();
              output.write(PNG);
              output.flush();
              bodyStalled.countDown();
              awaitQuietly(releaseBody);
              output.close();
            });
    SequencedHostResolver resolver = new SequencedHostResolver();
    resolver.enqueue(MEDIA_HOST, List.of(InetAddress.getByName(LOOPBACK)));
    HttpRemoteMediaTransport transport =
        new HttpRemoteMediaTransport(
            TIMEOUT, new PublicAddressDnsResolver(loopbackPolicy(resolver)));
    try {
      RemoteMediaTransport.MediaResponse response =
          transport.get(
              URI.create("http://" + MEDIA_HOST + ":" + server.getAddress().getPort() + "/media"),
              TIMEOUT);
      assertTrue(bodyStalled.await(5, TimeUnit.SECONDS), "server must stall the response body");

      AtomicReference<String> readerOutcome = new AtomicReference<>("blocked");
      CountDownLatch readerDone = new CountDownLatch(1);
      Thread reader =
          new Thread(
              () -> {
                try (InputStream body = response.body()) {
                  byte[] buffer = new byte[64];
                  while (body.read(buffer) != -1) {
                    // 读到停滞处后会一直阻塞，直到响应体被关闭
                  }
                  readerOutcome.set("eof");
                } catch (IOException error) {
                  readerOutcome.set("aborted");
                } finally {
                  readerDone.countDown();
                }
              });
      reader.setDaemon(true);
      reader.start();
      Thread.sleep(300);

      response.body().close();

      assertTrue(
          readerDone.await(5, TimeUnit.SECONDS),
          () ->
              "closing the response body must abort the blocked read, outcome="
                  + readerOutcome.get());
    } finally {
      releaseBody.countDown();
      transport.close();
      server.stop(0);
    }
  }

  // =========================================================================
  // 测试辅助与替身
  // =========================================================================

  /** 记录服务器侧观察到的请求，用于断言连接次数、请求头与请求体。 */
  private static final class Endpoint {

    private final AtomicInteger requests = new AtomicInteger();
    private volatile Headers headers = new Headers();
    private volatile byte[] body = new byte[0];

    private void record(HttpExchange exchange) throws IOException {
      requests.incrementAndGet();
      headers = new Headers(exchange.getRequestHeaders());
      body = exchange.getRequestBody().readAllBytes();
    }
  }

  /** 按入队顺序逐次给出解析结果的主机名替身：模拟 DNS 在两次解析之间从公网改到私网。 */
  private static final class SequencedHostResolver implements HostResolver {

    private final Map<String, Deque<List<InetAddress>>> answers = new HashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    private void enqueue(String host, List<InetAddress> addresses) {
      answers.computeIfAbsent(host, name -> new ArrayDeque<>()).add(addresses);
    }

    private int callCount() {
      return calls.get();
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
      calls.incrementAndGet();
      Deque<List<InetAddress>> queue = answers.get(host);
      List<InetAddress> answer = queue == null ? null : queue.poll();
      if (answer == null) {
        throw new UnknownHostException("No fake answer for host: " + host);
      }
      return answer;
    }
  }

  /** 仅测试使用的地址准入：在生产公网判定之外额外放行回环地址，使本地测试服务器可达，生产规则不放宽。 */
  private static PublicAddressPolicy loopbackPolicy(HostResolver resolver) {
    return new PublicAddressPolicy(
        resolver,
        address -> PublicAddressPolicy.isPublicAddress(address) || address.isLoopbackAddress());
  }

  /** 生产装配：系统解析 + 只放行公网地址；用于验证 URL 字面量私网地址同样被地址准入拒绝。 */
  private static HttpRemoteMediaTransport productionTransport() {
    return new HttpRemoteMediaTransport(
        TIMEOUT, new PublicAddressDnsResolver(new PublicAddressPolicy(HostResolver.system())));
  }

  private static HttpServer startPlainServer(HttpHandler handler) throws IOException {
    return startPlainServer(InetAddress.getByName(LOOPBACK), handler);
  }

  private static HttpServer startPlainServer(InetAddress bindAddress, HttpHandler handler)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, 0), 0);
    server.createContext("/", handler);
    server.setExecutor(null);
    server.start();
    return server;
  }

  /** 返回本机 IPv6 回环地址；环境没有 IPv6 时返回 null，让相关用例被跳过而不是误报失败。 */
  private static InetAddress ipv6LoopbackOrNull() {
    try {
      InetAddress loopback = InetAddress.getByName("::1");
      try (ServerSocket socket = new ServerSocket(0, 1, loopback)) {
        return loopback;
      }
    } catch (IOException error) {
      return null;
    }
  }

  /** 启动只监听回环、记录 SNI 的本地 HTTPS 服务器；TLS 材料由 {@link #createTestTlsContext()} 现场生成。 */
  private static HttpsServer startHttpsServer(
      SSLContext tls, List<String> requestedServerNames, HttpHandler handler) throws IOException {
    HttpsServer server =
        HttpsServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(tls) {
          @Override
          public void configure(HttpsParameters parameters) {
            SSLParameters sslParameters = getSSLContext().getDefaultSSLParameters();
            sslParameters.setSNIMatchers(
                List.of(
                    new SNIMatcher(0) {
                      @Override
                      public boolean matches(SNIServerName serverName) {
                        if (serverName instanceof SNIHostName hostName) {
                          requestedServerNames.add(hostName.getAsciiName());
                        }
                        return true;
                      }
                    }));
            parameters.setSSLParameters(sslParameters);
          }
        });
    server.createContext("/", handler);
    server.setExecutor(null);
    server.start();
    return server;
  }

  /**
   * 用 JDK 自带 keytool 现场生成自签身份：证书只签发给 {@code media.example.com}，仓库里不提交任何私钥材料。
   *
   * <p>返回的上下文同时作为服务器身份与客户端信任来源；客户端仍使用 HttpClient5 的默认主机名校验（{@link DefaultClientTlsStrategy}）。
   */
  private SSLContext createTestTlsContext() throws Exception {
    Path keystorePath = tempDir.resolve("test-identity.p12");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "media",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "1",
                "-dname",
                "CN=" + MEDIA_HOST,
                "-ext",
                "SAN=dns:" + MEDIA_HOST,
                "-keystore",
                keystorePath.toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                "changeit",
                "-keypass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), () -> "keytool failed: " + output);

    KeyStore identity = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(keystorePath)) {
      identity.load(input, "changeit".toCharArray());
    }
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(identity, "changeit".toCharArray());
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("media", (X509Certificate) identity.getCertificate("media"));
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trust);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    return context;
  }

  /** 记录请求并回应一张最小 PNG：媒体暂存路径需要的 200 + 正文。 */
  private static HttpHandler mediaHandler(Endpoint endpoint) {
    return exchange -> {
      endpoint.record(exchange);
      respond(exchange, 200, PNG, null);
    };
  }

  private static void respond(HttpExchange exchange, int status, byte[] body, String location)
      throws IOException {
    if (location != null) {
      exchange.getResponseHeaders().set("Location", location);
    }
    // 每次响应都结束连接，避免连接复用掩盖「每次解析都必须被校验」这条规则。
    exchange.getResponseHeaders().set("Connection", "close");
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static byte[] readAll(InputStream body) throws IOException {
    try (body) {
      return body.readAllBytes();
    }
  }

  /** 让测试服务器一直停滞到测试放行；被中断时直接返回。 */
  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private static String firstHeader(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      if (header.getKey() != null && header.getKey().equalsIgnoreCase(name)) {
        return header.getValue().isEmpty() ? null : header.getValue().get(0);
      }
    }
    return null;
  }
}
