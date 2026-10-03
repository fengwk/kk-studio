package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@Timeout(10)
class GitHttpConnectionFactoryTest {

  /** 意图：JGit 单一 timeout 不得覆盖分别设置的 connect/read；所有透传方法保留 JDK HTTP/TLS 契约。 */
  @Test
  void preservesSeparateTimeoutsAndHttpContract() throws Exception {
    RecordingConnection raw = new RecordingConnection(null, true);
    raw.responseCode = 200;
    raw.responseMessage = "OK";
    raw.headerFields.put("X-Test", List.of("one", "two"));
    raw.headerFields.put("x-null", null);
    raw.contentType = "text/plain";
    raw.contentLength = 2;
    raw.inputStream = new ByteArrayInputStream(new byte[] {1, 2});

    URL url = createUrl(raw);
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      HttpConnection connection = factory.create(url, Proxy.NO_PROXY);

      assertEquals(60_000, raw.getConnectTimeout());
      assertEquals(180_000, raw.getReadTimeout());
      assertEquals(1, raw.connectTimeoutSetCount.get());
      assertEquals(1, raw.readTimeoutSetCount.get());

      connection.setConnectTimeout(0);
      connection.setReadTimeout(42);
      assertEquals(60_000, raw.getConnectTimeout());
      assertEquals(180_000, raw.getReadTimeout());
      assertEquals(2, raw.connectTimeoutSetCount.get());
      assertEquals(2, raw.readTimeoutSetCount.get());

      connection.setRequestProperty("X", "Y");
      connection.setRequestMethod("POST");
      connection.setUseCaches(false);
      connection.setInstanceFollowRedirects(false);
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(2);
      connection.setChunkedStreamingMode(0);
      HostnameVerifier verifier =
          (host, session) -> {
            return false;
          };
      connection.setHostnameVerifier(verifier);
      connection.configure(null, null, null);

      assertEquals(url, connection.getURL());
      assertEquals(200, connection.getResponseCode());
      assertEquals("OK", connection.getResponseMessage());
      assertEquals(raw.headerFields, connection.getHeaderFields());
      assertEquals(List.of("one", "two"), connection.getHeaderFields("x-test"));
      assertEquals(List.of("one", "two"), connection.getHeaderFields("X-TEST"));
      assertTrue(connection.getHeaderFields("missing").isEmpty());
      assertTrue(connection.getHeaderFields("x-null").isEmpty());
      assertEquals("one", connection.getHeaderField("X-Test"));
      assertEquals("one", connection.getHeaderField("x-test"));
      assertNull(connection.getHeaderField("missing"));
      assertEquals("text/plain", connection.getContentType());
      assertEquals(2, connection.getContentLength());
      assertEquals("POST", connection.getRequestMethod());
      assertTrue(connection.usingProxy());

      assertEquals("Y", raw.getRequestProperty("X"));
      assertEquals("POST", raw.getRequestMethod());
      assertFalse(raw.getUseCaches());
      assertFalse(raw.getInstanceFollowRedirects());
      assertTrue(raw.getDoOutput());
      assertEquals(2, raw.fixedLengthStreamingMode);
      assertEquals(0, raw.chunkedStreamingMode);
      assertSame(verifier, raw.getHostnameVerifier());
      assertNotNull(raw.getSSLSocketFactory());

      connection.getOutputStream().write(3);
      try (InputStream input = connection.getInputStream()) {
        assertEquals(1, input.read());
        assertArrayEquals(new byte[] {2}, input.readAllBytes());
      }
      assertArrayEquals(new byte[] {3}, raw.outputStream.toByteArray());
      assertEquals(1, raw.disconnectCount.get());
    }
  }

  /** 意图：连接与读取失败有稳定、不同的分类并释放连接；超时绝不能进入 exact commit 回退。 */
  @Test
  void classifiesFailuresAndNeverRetriesTimeouts() throws Exception {
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory(30, 90)) {
      RecordingConnection connectFail = new RecordingConnection(null);
      connectFail.connectFailure = new SocketTimeoutException("connect");
      HttpConnection connectConn = factory.create(createUrl(connectFail));
      IOException connect = assertThrows(IOException.class, connectConn::getResponseCode);
      assertEquals("GIT_CONNECT_TIMEOUT", GitHttpConnectionFactory.failureCode(connect));
      assertFalse(GitHttpConnectionFactory.canFallback(connect));
      assertTrue(connectFail.disconnectCount.get() >= 1);

      RecordingConnection plainConnectFail = new RecordingConnection(null);
      plainConnectFail.connectFailure = new IOException("reset");
      HttpConnection plainConnectConn = factory.create(createUrl(plainConnectFail));
      IOException plainConnect = assertThrows(IOException.class, plainConnectConn::connect);
      assertEquals("reset", plainConnect.getMessage());
      assertEquals("GIT_FETCH_FAILED", GitHttpConnectionFactory.failureCode(plainConnect));
      assertTrue(plainConnectFail.disconnectCount.get() >= 1);

      RecordingConnection headerFail = new RecordingConnection(null);
      headerFail.responseCodeFailure = new SocketTimeoutException("headers");
      HttpConnection headerConn = factory.create(createUrl(headerFail));
      IOException headers = assertThrows(IOException.class, headerConn::getResponseCode);
      assertEquals("GIT_READ_TIMEOUT", GitHttpConnectionFactory.failureCode(headers));
      assertFalse(GitHttpConnectionFactory.canFallback(headers));
      assertTrue(headerFail.disconnectCount.get() >= 1);

      RecordingConnection plainHeaderFail = new RecordingConnection(null);
      plainHeaderFail.responseCodeFailure = new IOException("502 bad gateway");
      HttpConnection plainHeaderConn = factory.create(createUrl(plainHeaderFail));
      IOException plainHeaders = assertThrows(IOException.class, plainHeaderConn::getResponseCode);
      assertEquals("502 bad gateway", plainHeaders.getMessage());
      assertEquals("GIT_FETCH_FAILED", GitHttpConnectionFactory.failureCode(plainHeaders));
      assertTrue(plainHeaderFail.disconnectCount.get() >= 1);

      RecordingConnection inputFail = new RecordingConnection(null);
      inputFail.inputStreamFailure = new SocketTimeoutException("body");
      HttpConnection inputConn = factory.create(createUrl(inputFail));
      IOException body = assertThrows(IOException.class, inputConn::getInputStream);
      assertEquals("GIT_READ_TIMEOUT", GitHttpConnectionFactory.failureCode(body));
      assertFalse(GitHttpConnectionFactory.canFallback(body));
      assertTrue(inputFail.disconnectCount.get() >= 1);

      RecordingConnection plainInputFail = new RecordingConnection(null);
      plainInputFail.inputStreamFailure = new IOException("stream unavailable");
      HttpConnection plainInputConn = factory.create(createUrl(plainInputFail));
      IOException plainBody = assertThrows(IOException.class, plainInputConn::getInputStream);
      assertEquals("stream unavailable", plainBody.getMessage());
      assertEquals("GIT_FETCH_FAILED", GitHttpConnectionFactory.failureCode(plainBody));
      assertTrue(plainInputFail.disconnectCount.get() >= 1);

      RecordingConnection outputFail = new RecordingConnection(null);
      outputFail.outputStreamFailure = new IOException("closed");
      HttpConnection outputConn = factory.create(createUrl(outputFail));
      IOException outError = assertThrows(IOException.class, outputConn::getOutputStream);
      assertEquals("closed", outError.getMessage());
      assertEquals("GIT_FETCH_FAILED", GitHttpConnectionFactory.failureCode(outError));
      assertTrue(outputFail.disconnectCount.get() >= 1);

      RecordingConnection outputTimeoutFail = new RecordingConnection(null);
      outputTimeoutFail.outputStreamFailure = new SocketTimeoutException("pipe timeout");
      HttpConnection outputTimeoutConn = factory.create(createUrl(outputTimeoutFail));
      IOException outTimeout = assertThrows(IOException.class, outputTimeoutConn::getOutputStream);
      assertEquals("GIT_READ_TIMEOUT", GitHttpConnectionFactory.failureCode(outTimeout));
      assertTrue(outputTimeoutFail.disconnectCount.get() >= 1);

      RecordingConnection stalled = new RecordingConnection(null);
      stalled.inputStream = new FailingInputStream(new SocketTimeoutException("stall"));
      HttpConnection stalledConn = factory.create(createUrl(stalled));
      try (InputStream input = stalledConn.getInputStream()) {
        SocketTimeoutException read1 = assertThrows(SocketTimeoutException.class, input::read);
        assertEquals("GIT_READ_TIMEOUT", read1.getMessage());
        SocketTimeoutException read2 =
            assertThrows(
                SocketTimeoutException.class,
                () -> {
                  input.read(new byte[8]);
                });
        assertEquals("GIT_READ_TIMEOUT", read2.getMessage());
      }
      assertTrue(stalled.disconnectCount.get() >= 2);

      RecordingConnection brokenStream = new RecordingConnection(null);
      brokenStream.inputStream = new FailingInputStream(new IOException("broken pipe"));
      HttpConnection brokenConn = factory.create(createUrl(brokenStream));
      try (InputStream input = brokenConn.getInputStream()) {
        IOException read1 = assertThrows(IOException.class, input::read);
        assertEquals("broken pipe", read1.getMessage());
        IOException read2 =
            assertThrows(
                IOException.class,
                () -> {
                  input.read(new byte[8], 0, 4);
                });
        assertEquals("broken pipe", read2.getMessage());
      }

      assertTrue(GitHttpConnectionFactory.canFallback(new RefNotAdvertisedException("exact")));
      assertTrue(GitHttpConnectionFactory.canFallback(new IOException("not our ref")));
      assertTrue(GitHttpConnectionFactory.canFallback(new IOException("unadvertised object")));
      assertTrue(
          GitHttpConnectionFactory.canFallback(
              new IOException("want " + "a".repeat(40) + " not valid")));
      assertTrue(
          GitHttpConnectionFactory.canFallback(
              new IOException(
                  "prefix\nwant "
                      + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef not valid\nsuffix")));
      assertFalse(
          GitHttpConnectionFactory.canFallback(
              new IOException("want " + "a".repeat(39) + " not valid")));
      assertFalse(
          GitHttpConnectionFactory.canFallback(
              new IOException("want " + "g".repeat(40) + " not valid")));
      assertFalse(GitHttpConnectionFactory.canFallback(new IOException("authentication failed")));
      assertFalse(GitHttpConnectionFactory.canFallback(new IOException()));
      assertFalse(GitHttpConnectionFactory.canFallback(null));

      assertFalse(GitHttpConnectionFactory.canFallback(new SocketTimeoutException("not our ref")));
      assertFalse(
          GitHttpConnectionFactory.canFallback(
              new IOException("not our ref", new SocketTimeoutException("GIT_CONNECT_TIMEOUT"))));
      assertFalse(
          GitHttpConnectionFactory.canFallback(
              new IOException("unadvertised object", new SocketTimeoutException("timeout"))));
      assertFalse(
          GitHttpConnectionFactory.canFallback(
              new IOException("not our ref", new InterruptedIOException("cancelled"))));
      assertEquals(
          "UNSUPPORTED_REPOSITORY_SCHEME",
          GitHttpConnectionFactory.failureCode(
              new GitHttpConnectionFactory.UnsupportedTransportException("git://host/repo")));

      assertEquals(
          "GIT_CONNECT_TIMEOUT",
          GitHttpConnectionFactory.failureCode(new SocketTimeoutException("GIT_CONNECT_TIMEOUT")));
      assertEquals(
          "GIT_CONNECT_TIMEOUT",
          GitHttpConnectionFactory.failureCode(
              new RuntimeException(new SocketTimeoutException("GIT_CONNECT_TIMEOUT"))));
      assertEquals(
          "GIT_READ_TIMEOUT",
          GitHttpConnectionFactory.failureCode(new SocketTimeoutException("GIT_READ_TIMEOUT")));
      assertEquals(
          "GIT_READ_TIMEOUT",
          GitHttpConnectionFactory.failureCode(new SocketTimeoutException("connect timed out")));
      assertEquals(
          "GIT_READ_TIMEOUT", GitHttpConnectionFactory.failureCode(new SocketTimeoutException()));
      assertEquals(
          "GIT_FETCH_FAILED",
          GitHttpConnectionFactory.failureCode(new IOException("network error")));
      assertEquals("GIT_FETCH_FAILED", GitHttpConnectionFactory.failureCode(null));

      RecordingConnection closeTarget = new RecordingConnection(null);
      URL closeUrl = createUrl(closeTarget);
      factory.close();
      assertThrows(
          IOException.class,
          () -> {
            factory.create(closeUrl);
          });
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          new GitHttpConnectionFactory(0, 1);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          new GitHttpConnectionFactory(-1, 1);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          new GitHttpConnectionFactory(1, 0);
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          new GitHttpConnectionFactory(1, -1);
        });
  }

  /** 意图：取消无限总等待时实际断开网络，不等 production read idle 到期。 */
  @Test
  void interruptionClosesInFlightConnection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch disconnected = new CountDownLatch(1);
    RecordingConnection raw = new RecordingConnection(null);
    URL url = createUrl(raw);
    raw.onGetResponseCode =
        () -> {
          entered.countDown();
          while (disconnected.getCount() != 0) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
          }
          throw new IOException("closed");
        };
    raw.onDisconnect =
        () -> {
          disconnected.countDown();
        };

    CompletableFuture<Void> done = new CompletableFuture<>();
    Thread owner =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
                    assertThrows(
                        IOException.class,
                        () -> {
                          factory.create(url).getResponseCode();
                        });
                    assertThrows(
                        IOException.class,
                        () -> {
                          factory.create(url);
                        });
                    done.complete(null);
                  } catch (Throwable error) {
                    done.completeExceptionally(error);
                  }
                });

    assertTrue(entered.await(2, TimeUnit.SECONDS));
    owner.interrupt();
    done.get(2, TimeUnit.SECONDS);
    assertTrue(disconnected.await(2, TimeUnit.SECONDS));
  }

  /** 意图：callback 只为 http/https 配置连接工厂，放行本地 file，其他协议 fail-closed。 */
  @Test
  void callbackConfiguresTransportHttpOnly(@TempDir Path directory) throws Exception {
    try (Repository repo = Git.init().setDirectory(directory.toFile()).call().getRepository();
        GitHttpConnectionFactory factory = new GitHttpConnectionFactory(1000, 2000)) {
      URIish httpUri = new URIish("http://127.0.0.1/test.git");
      try (Transport transport = Transport.open(repo, httpUri)) {
        assertTrue(transport instanceof TransportHttp);
        factory.callback().configure(transport);
        assertSame(factory, ((TransportHttp) transport).getHttpConnectionFactory());
      }

      URIish fileUri = new URIish("file:///tmp/repo.git");
      try (Transport fileTransport = new NonHttpTransport(fileUri)) {
        assertFalse(fileTransport instanceof TransportHttp);
        assertDoesNotThrow(() -> factory.callback().configure(fileTransport));
      }

      URIish gitUri = new URIish("git://example.test/repo.git");
      try (Transport gitTransport = new NonHttpTransport(gitUri)) {
        GitHttpConnectionFactory.UnsupportedTransportException error =
            assertThrows(
                GitHttpConnectionFactory.UnsupportedTransportException.class,
                () -> factory.callback().configure(gitTransport));
        assertEquals("UNSUPPORTED_REPOSITORY_SCHEME", GitHttpConnectionFactory.failureCode(error));
      }
    }
  }

  /** 意图：调用线程已处于中断状态时，create 立即抛出 InterruptedIOException。 */
  @Test
  void createRejectsInterruptedCallingThread() throws Exception {
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      RecordingConnection raw = new RecordingConnection(null);
      URL url = createUrl(raw);
      Thread.currentThread().interrupt();
      try {
        InterruptedIOException ex =
            assertThrows(
                InterruptedIOException.class,
                () -> {
                  factory.create(url);
                });
        assertTrue(ex.getMessage().contains("cancelled"));
      } finally {
        Thread.interrupted();
      }
    }
  }

  /** 意图：工厂 close() 会批量断开所有追踪的未完成连接并停止 cancellation 观察。 */
  @Test
  void closeDisconnectsTrackedConnections() throws Exception {
    RecordingConnection raw1 = new RecordingConnection(null);
    RecordingConnection raw2 = new RecordingConnection(null);
    URL url1 = createUrl(raw1);
    URL url2 = createUrl(raw2);

    GitHttpConnectionFactory factory = new GitHttpConnectionFactory();
    factory.create(url1);
    factory.create(url2);

    assertEquals(0, raw1.disconnectCount.get());
    assertEquals(0, raw2.disconnectCount.get());

    factory.close();
    assertEquals(1, raw1.disconnectCount.get());
    assertEquals(1, raw2.disconnectCount.get());

    factory.close();
    assertEquals(1, raw1.disconnectCount.get());
    assertEquals(1, raw2.disconnectCount.get());
  }

  private static URL createUrl(RecordingConnection connection) throws Exception {
    URL url =
        new URL(
            null,
            "https://local.test/repo",
            new URLStreamHandler() {
              @Override
              protected URLConnection openConnection(URL u) {
                return connection;
              }

              @Override
              protected URLConnection openConnection(URL u, Proxy proxy) {
                return connection;
              }
            });
    connection.setUrl(url);
    return url;
  }

  /** 意图：close 与 connection 创建竞态不能遗漏新连接，已关闭操作也不能重新 connect。 */
  @Test
  void cancellationDuringCreationReleasesNewConnection() throws Exception {
    RecordingConnection raw = new RecordingConnection(null);
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      URL closing =
          new URL(
              null,
              "https://local.test/repo",
              new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                  factory.close();
                  return raw;
                }
              });
      assertThrows(IOException.class, () -> factory.create(closing));
      assertEquals(1, raw.disconnectCount.get());
    }
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      HttpConnection connection = factory.create(createUrl(raw));
      factory.close();
      assertThrows(IOException.class, connection::connect);
      assertEquals(0, raw.connectCount.get());
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws IOException;
  }

  private static final class FailingInputStream extends InputStream {
    private final IOException failure;

    private FailingInputStream(IOException failure) {
      this.failure = failure;
    }

    @Override
    public int read() throws IOException {
      throw failure;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      throw failure;
    }
  }

  private static final class NonHttpTransport extends Transport {
    private NonHttpTransport(URIish uri) {
      super(uri);
    }

    @Override
    public FetchConnection openFetch() {
      return null;
    }

    @Override
    public PushConnection openPush() {
      return null;
    }

    @Override
    public void close() {}
  }

  private static final class RecordingConnection extends HttpsURLConnection {
    private final boolean proxyUsed;
    private final AtomicInteger connectCount = new AtomicInteger();
    private final AtomicInteger disconnectCount = new AtomicInteger();
    private final AtomicInteger connectTimeoutSetCount = new AtomicInteger();
    private final AtomicInteger readTimeoutSetCount = new AtomicInteger();
    private final Map<String, List<String>> headerFields = new LinkedHashMap<>();
    private final Map<String, String> requestProperties = new LinkedHashMap<>();
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    private int responseCode = 200;
    private String responseMessage = "OK";
    private String contentType = "text/plain";
    private int contentLength = 0;
    private InputStream inputStream = new ByteArrayInputStream(new byte[0]);
    private int fixedLengthStreamingMode = -1;
    private int chunkedStreamingMode = -1;
    private HostnameVerifier hostnameVerifier;
    private SSLSocketFactory sslSocketFactory;

    private IOException connectFailure;
    private IOException responseCodeFailure;
    private IOException inputStreamFailure;
    private IOException outputStreamFailure;
    private Runnable onDisconnect;
    private ThrowingSupplier<Integer> onGetResponseCode;

    private RecordingConnection(URL url) {
      this(url, false);
    }

    private RecordingConnection(URL url, boolean proxyUsed) {
      super(url);
      this.proxyUsed = proxyUsed;
    }

    private void setUrl(URL url) {
      this.url = url;
    }

    @Override
    public void setConnectTimeout(int timeout) {
      super.setConnectTimeout(timeout);
      connectTimeoutSetCount.incrementAndGet();
    }

    @Override
    public void setReadTimeout(int timeout) {
      super.setReadTimeout(timeout);
      readTimeoutSetCount.incrementAndGet();
    }

    @Override
    public void connect() throws IOException {
      connectCount.incrementAndGet();
      if (connectFailure != null) {
        throw connectFailure;
      }
      this.connected = true;
    }

    @Override
    public void disconnect() {
      disconnectCount.incrementAndGet();
      if (onDisconnect != null) {
        onDisconnect.run();
      }
    }

    @Override
    public boolean usingProxy() {
      return proxyUsed;
    }

    @Override
    public int getResponseCode() throws IOException {
      if (responseCodeFailure != null) {
        throw responseCodeFailure;
      }
      if (onGetResponseCode != null) {
        return onGetResponseCode.get();
      }
      return responseCode;
    }

    @Override
    public String getResponseMessage() throws IOException {
      return responseMessage;
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
      return headerFields;
    }

    @Override
    public String getHeaderField(String name) {
      if (name == null) {
        return null;
      }
      for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
        if (name.equalsIgnoreCase(entry.getKey())
            && entry.getValue() != null
            && !entry.getValue().isEmpty()) {
          return entry.getValue().get(0);
        }
      }
      return null;
    }

    @Override
    public String getContentType() {
      return contentType;
    }

    @Override
    public int getContentLength() {
      return contentLength;
    }

    @Override
    public void setRequestProperty(String key, String value) {
      requestProperties.put(key, value);
    }

    @Override
    public String getRequestProperty(String key) {
      return requestProperties.get(key);
    }

    @Override
    public InputStream getInputStream() throws IOException {
      if (inputStreamFailure != null) {
        throw inputStreamFailure;
      }
      return inputStream;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      if (outputStreamFailure != null) {
        throw outputStreamFailure;
      }
      return outputStream;
    }

    @Override
    public void setFixedLengthStreamingMode(int length) {
      this.fixedLengthStreamingMode = length;
    }

    @Override
    public void setChunkedStreamingMode(int length) {
      this.chunkedStreamingMode = length;
    }

    @Override
    public void setHostnameVerifier(HostnameVerifier verifier) {
      this.hostnameVerifier = verifier;
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
      return hostnameVerifier;
    }

    @Override
    public void setSSLSocketFactory(SSLSocketFactory factory) {
      this.sslSocketFactory = factory;
    }

    @Override
    public SSLSocketFactory getSSLSocketFactory() {
      return sslSocketFactory;
    }

    @Override
    public String getCipherSuite() {
      return "TLS_AES_256_GCM_SHA384";
    }

    @Override
    public Certificate[] getLocalCertificates() {
      return null;
    }

    @Override
    public Certificate[] getServerCertificates() {
      return null;
    }
  }
}
