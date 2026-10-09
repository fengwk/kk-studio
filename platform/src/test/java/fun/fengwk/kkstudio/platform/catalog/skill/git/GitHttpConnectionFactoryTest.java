package fun.fengwk.kkstudio.platform.catalog.skill.git;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.HttpsURLConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@Timeout(10)
class GitHttpConnectionFactoryTest {

  /** 意图：JGit 的单一 timeout 不能覆盖 connect/read；所有透传方法保留 JDK HTTP/TLS 行为。 */
  @Test
  void preservesSeparateTimeoutsAndHttpContract() throws Exception {
    HttpsURLConnection raw = mock(HttpsURLConnection.class);
    URL url = url(raw);
    when(raw.getURL()).thenReturn(url);
    when(raw.getResponseCode()).thenReturn(200);
    when(raw.getResponseMessage()).thenReturn("OK");
    when(raw.getHeaderFields()).thenReturn(Map.of("X-Test", List.of("one", "two")));
    when(raw.getHeaderField("X-Test")).thenReturn("one");
    when(raw.getContentType()).thenReturn("text/plain");
    when(raw.getContentLength()).thenReturn(2);
    when(raw.getRequestMethod()).thenReturn("POST");
    when(raw.usingProxy()).thenReturn(true);
    when(raw.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[] {1, 2}));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    when(raw.getOutputStream()).thenReturn(output);
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      HttpConnection connection = factory.create(url, Proxy.NO_PROXY);
      connection.setConnectTimeout(0);
      connection.setReadTimeout(42);
      verify(raw, times(2)).setConnectTimeout(60_000);
      verify(raw, times(2)).setReadTimeout(180_000);
      connection.setRequestProperty("X", "Y");
      connection.setRequestMethod("POST");
      connection.setUseCaches(false);
      connection.setInstanceFollowRedirects(false);
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(2);
      connection.setChunkedStreamingMode(0);
      connection.setHostnameVerifier((host, session) -> false);
      connection.configure(null, null, null);
      assertEquals(url, connection.getURL());
      assertEquals(200, connection.getResponseCode());
      assertEquals("OK", connection.getResponseMessage());
      assertEquals(Map.of("X-Test", List.of("one", "two")), connection.getHeaderFields());
      assertEquals(List.of("one", "two"), connection.getHeaderFields("x-test"));
      assertEquals("one", connection.getHeaderField("X-Test"));
      assertEquals("text/plain", connection.getContentType());
      assertEquals(2, connection.getContentLength());
      assertEquals("POST", connection.getRequestMethod());
      assertTrue(connection.usingProxy());
      connection.getOutputStream().write(3);
      try (InputStream input = connection.getInputStream()) {
        assertEquals(1, input.read());
        assertArrayEquals(new byte[] {2}, input.readAllBytes());
      }
      assertArrayEquals(new byte[] {3}, output.toByteArray());
      verify(raw).disconnect();
      verify(raw).setRequestProperty("X", "Y");
      verify(raw).setSSLSocketFactory(any());
    }
  }

  /** 意图：连接与读取失败有稳定、不同的分类并释放连接；超时是终态，绝不重试。 */
  @Test
  void classifiesFailuresAndNeverRetriesTimeouts() throws Exception {
    HttpURLConnection raw = mock(HttpURLConnection.class);
    URL url = url(raw);
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory(30, 90)) {
      HttpConnection connection = factory.create(url);
      doThrow(new SocketTimeoutException("connect")).when(raw).connect();
      IOException connect = assertThrows(IOException.class, connection::getResponseCode);
      assertEquals("GIT_CONNECT_TIMEOUT", GitHttpConnectionFactory.failureCode(connect));
      doNothing().when(raw).connect();
      when(raw.getResponseCode()).thenThrow(new SocketTimeoutException("headers"));
      IOException headers = assertThrows(IOException.class, connection::getResponseCode);
      assertEquals("GIT_READ_TIMEOUT", GitHttpConnectionFactory.failureCode(headers));
      when(raw.getInputStream()).thenThrow(new SocketTimeoutException("body"));
      assertEquals(
          "GIT_READ_TIMEOUT",
          GitHttpConnectionFactory.failureCode(
              assertThrows(IOException.class, connection::getInputStream)));
      when(raw.getOutputStream()).thenThrow(new IOException("closed"));
      assertEquals(
          "closed", assertThrows(IOException.class, connection::getOutputStream).getMessage());
      InputStream stalled = mock(InputStream.class);
      when(stalled.read()).thenThrow(new SocketTimeoutException());
      when(stalled.read(any(byte[].class), anyInt(), anyInt()))
          .thenThrow(new SocketTimeoutException());
      doReturn(stalled).when(raw).getInputStream();
      try (InputStream input = connection.getInputStream()) {
        assertThrows(SocketTimeoutException.class, input::read);
        assertThrows(SocketTimeoutException.class, () -> input.read(new byte[8]));
      }
      verify(raw, atLeast(6)).disconnect();
      assertEquals("GIT_FETCH_FAILED", GitHttpConnectionFactory.failureCode(new IOException()));
      assertEquals(
          "UNSUPPORTED_REPOSITORY_SCHEME",
          GitHttpConnectionFactory.failureCode(
              new GitHttpConnectionFactory.UnsupportedTransportException("git://host/repo")));
      factory.close();
      assertThrows(IOException.class, () -> factory.create(url));
    }
    assertThrows(IllegalArgumentException.class, () -> new GitHttpConnectionFactory(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new GitHttpConnectionFactory(1, 0));
  }

  /** 意图：真实 HTTP socket 无数据时停止；持续数据超过 read budget 仍完成，不依赖进度文本。 */
  @Test
  void boundsReadIdleButNotWholeTransfer() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch release = new CountDownLatch(1);
    var scheduler = Executors.newSingleThreadScheduledExecutor();
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(workers);
    server.createContext(
        "/idle",
        exchange -> {
          exchange.sendResponseHeaders(200, 1);
          try {
            release.await();
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.createContext(
        "/flow",
        exchange -> {
          exchange.sendResponseHeaders(200, 8);
          AtomicInteger sent = new AtomicInteger();
          CompletableFuture<Void> done = new CompletableFuture<>();
          var writer =
              scheduler.scheduleAtFixedRate(
                  () -> {
                    try {
                      if (sent.incrementAndGet() <= 8) {
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                      } else {
                        done.complete(null);
                      }
                    } catch (IOException error) {
                      done.completeExceptionally(error);
                    }
                  },
                  0,
                  60,
                  TimeUnit.MILLISECONDS);
          try {
            done.join();
          } finally {
            writer.cancel(false);
            exchange.close();
          }
        });
    server.start();
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory(100, 200)) {
      URL base = new URL("http://127.0.0.1:" + server.getAddress().getPort());
      HttpConnection idle = factory.create(new URL(base, "/idle"), Proxy.NO_PROXY);
      assertEquals(200, idle.getResponseCode());
      try (InputStream input = idle.getInputStream()) {
        assertEquals(
            "GIT_READ_TIMEOUT",
            GitHttpConnectionFactory.failureCode(
                assertThrows(SocketTimeoutException.class, input::read)));
      }
      long start = System.nanoTime();
      try (InputStream input =
          factory.create(new URL(base, "/flow"), Proxy.NO_PROXY).getInputStream()) {
        assertEquals(8, input.readAllBytes().length);
      }
      assertTrue(System.nanoTime() - start > TimeUnit.MILLISECONDS.toNanos(200));
    } finally {
      release.countDown();
      scheduler.shutdownNow();
      server.stop(0);
      workers.shutdownNow();
    }
  }

  /** 意图：取消无限总等待时实际断开网络，不等 production read idle 到期。 */
  @Test
  void interruptionClosesInFlightConnection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch disconnected = new CountDownLatch(1);
    HttpURLConnection raw = mock(HttpURLConnection.class);
    URL url = url(raw);
    when(raw.getResponseCode())
        .thenAnswer(
            invocation -> {
              entered.countDown();
              while (disconnected.getCount() != 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
              }
              throw new IOException("closed");
            });
    doAnswer(
            invocation -> {
              disconnected.countDown();
              return null;
            })
        .when(raw)
        .disconnect();
    CompletableFuture<Void> done = new CompletableFuture<>();
    Thread owner =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
                    assertThrows(IOException.class, () -> factory.create(url).getResponseCode());
                    assertThrows(IOException.class, () -> factory.create(url));
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

  private static URL url(HttpURLConnection raw) throws Exception {
    return new URL(
        null,
        "https://local.test/repo",
        new URLStreamHandler() {
          @Override
          protected URLConnection openConnection(URL url) {
            return raw;
          }

          @Override
          protected URLConnection openConnection(URL url, Proxy proxy) {
            return raw;
          }
        });
  }

  /** 意图：close 与 connection 创建竞态不能遗漏新连接，已关闭操作也不能重新 connect。 */
  @Test
  void cancellationDuringCreationReleasesNewConnection() throws Exception {
    HttpURLConnection raw = mock(HttpURLConnection.class);
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
      verify(raw).disconnect();
    }
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      HttpConnection connection = factory.create(url(raw));
      factory.close();
      assertThrows(IOException.class, connection::connect);
      verify(raw, never()).connect();
    }
  }

  /** 意图：callback 只接受 http/https 与本地 file；其他 scheme 一律 fail-closed。 */
  @Test
  void callbackOnlyAcceptsHttpHttpsAndFileSchemes() throws Exception {
    try (GitHttpConnectionFactory factory = new GitHttpConnectionFactory()) {
      Transport http = mock(Transport.class);
      when(http.getURI()).thenReturn(new URIish("https://example.test/repo.git"));
      assertDoesNotThrow(() -> factory.callback().configure(http));

      Transport file = mock(Transport.class);
      when(file.getURI()).thenReturn(new URIish("file:///tmp/repo.git"));
      assertDoesNotThrow(() -> factory.callback().configure(file));

      for (String unsupported :
          List.of("git://example.test/repo.git", "ssh://example.test/repo.git")) {
        Transport transport = mock(Transport.class);
        when(transport.getURI()).thenReturn(new URIish(unsupported));
        assertThrows(
            GitHttpConnectionFactory.UnsupportedTransportException.class,
            () -> factory.callback().configure(transport));
      }

      Transport withoutUri = mock(Transport.class);
      when(withoutUri.getURI()).thenReturn(null);
      assertThrows(
          GitHttpConnectionFactory.UnsupportedTransportException.class,
          () -> factory.callback().configure(withoutUri));
    }
  }
}
