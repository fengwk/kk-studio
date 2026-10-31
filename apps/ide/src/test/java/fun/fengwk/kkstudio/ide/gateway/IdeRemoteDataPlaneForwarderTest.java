package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

class IdeRemoteDataPlaneForwarderTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final UUID VIEW = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Test
  void readHeaderBlockParsesStatusHeadersAndRemaining() throws Exception {
    String raw =
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
            + "\r\n"
            + "extra-bytes";

    try (AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();

      try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
        client.connect(address).get();
        try (AsynchronousSocketChannel accepted = server.accept().get()) {
          accepted.write(ByteBuffer.wrap(raw.getBytes(StandardCharsets.ISO_8859_1))).get();

          IdeRemoteDataPlaneForwarder.HttpResponseHeader head =
              IdeRemoteDataPlaneForwarder.readHeaderBlock(client, 1024, 2000);

          assertEquals(101, head.status());
          assertEquals(List.of("websocket"), head.headers().get("Upgrade"));
          assertNotNull(head.remainingBytes());
          assertEquals(
              "extra-bytes", new String(head.remainingBytes(), StandardCharsets.ISO_8859_1));
        }
      }
    }
  }

  @Test
  void readHeaderBlockThrowsWhenExceedingLimit() throws Exception {
    byte[] huge = new byte[2000];
    try (AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();

      try (AsynchronousSocketChannel client = AsynchronousSocketChannel.open()) {
        client.connect(address).get();
        try (AsynchronousSocketChannel accepted = server.accept().get()) {
          accepted.write(ByteBuffer.wrap(huge)).get();

          assertThrows(
              IOException.class,
              () -> IdeRemoteDataPlaneForwarder.readHeaderBlock(client, 100, 2000));
        }
      }
    }
  }

  @Test
  void forwardHttpFiltersDisallowedJdkHeaders() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    IdeClusterClient clusterClient = mock(IdeClusterClient.class);
    ClusterOwnerRoute owner =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:8080"));

    when(clusterClient.signHeaders(any(), any(), any()))
        .thenReturn(Map.of("X-KK-Cluster-Signature", "sig123"));

    HttpResponse<InputStream> httpResponse = mock(HttpResponse.class);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.headers())
        .thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/plain")), (k, v) -> true));
    when(httpResponse.body())
        .thenReturn(new ByteArrayInputStream("response-payload".getBytes(StandardCharsets.UTF_8)));

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    when(httpClient.send(captor.capture(), any())).thenReturn((HttpResponse) httpResponse);

    IdeRemoteDataPlaneForwarder forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("POST");
    when(req.getContextPath()).thenReturn("");
    when(req.getHeaderNames())
        .thenReturn(
            Collections.enumeration(
                List.of(
                    "Host",
                    "Content-Length",
                    "Connection",
                    "Upgrade",
                    "Transfer-Encoding",
                    "Expect",
                    "X-Custom-App")));
    when(req.getHeaders("Host")).thenReturn(Collections.enumeration(List.of("evil.com")));
    when(req.getHeaders("Content-Length")).thenReturn(Collections.enumeration(List.of("100")));
    when(req.getHeaders("Connection")).thenReturn(Collections.enumeration(List.of("Upgrade")));
    when(req.getHeaders("Upgrade")).thenReturn(Collections.enumeration(List.of("websocket")));
    when(req.getHeaders("Transfer-Encoding"))
        .thenReturn(Collections.enumeration(List.of("chunked")));
    when(req.getHeaders("Expect")).thenReturn(Collections.enumeration(List.of("100-continue")));
    when(req.getHeaders("X-Custom-App")).thenReturn(Collections.enumeration(List.of("test-value")));
    when(req.getHeader("Cookie")).thenReturn("session=abc");

    HttpServletResponse res = mock(HttpServletResponse.class);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ServletOutputStream servletOut =
        new ServletOutputStream() {
          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener writeListener) {}

          @Override
          public void write(int b) {
            out.write(b);
          }
        };
    when(res.getOutputStream()).thenReturn(servletOut);

    forwarder.forwardHttp(req, res, owner, ENV, VIEW, "/src/main.ts");

    HttpRequest sentRequest = captor.getValue();
    HttpHeaders sentHeaders = sentRequest.headers();
    // Prove that restricted JDK headers are completely excluded:
    assertFalse(sentHeaders.firstValue("host").isPresent());
    assertFalse(sentHeaders.firstValue("content-length").isPresent());
    assertFalse(sentHeaders.firstValue("connection").isPresent());
    assertFalse(sentHeaders.firstValue("upgrade").isPresent());
    assertFalse(sentHeaders.firstValue("transfer-encoding").isPresent());
    assertFalse(sentHeaders.firstValue("expect").isPresent());
    assertEquals("test-value", sentHeaders.firstValue("x-custom-app").orElse(null));
  }

  @Test
  void forwardWebSocketRejectionSendsStatusOnlyWithoutReadingBody() throws Exception {
    try (AsynchronousServerSocketChannel server =
        AsynchronousServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      InetSocketAddress address = (InetSocketAddress) server.getLocalAddress();
      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(
              UUID.randomUUID(), URI.create("http://127.0.0.1:" + address.getPort()));

      HttpClient httpClient = mock(HttpClient.class);
      IdeClusterClient clusterClient = mock(IdeClusterClient.class);
      when(clusterClient.signHeaders(any(), any(), any()))
          .thenReturn(Map.of("X-KK-Cluster-Signature", "sig123"));

      IdeRemoteDataPlaneForwarder forwarder =
          new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

      Executors.newSingleThreadExecutor()
          .submit(
              () -> {
                try {
                  AsynchronousSocketChannel accepted = server.accept().get();
                  ByteBuffer readBuf = ByteBuffer.allocate(1024);
                  accepted.read(readBuf).get();
                  String response =
                      "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nview-not-found-body";
                  accepted
                      .write(ByteBuffer.wrap(response.getBytes(StandardCharsets.ISO_8859_1)))
                      .get();
                } catch (Exception ignored) {
                }
              });

      HttpServletRequest req = mock(HttpServletRequest.class);
      when(req.getContextPath()).thenReturn("");
      when(req.getHeader("Sec-WebSocket-Key")).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
      when(req.getHeader("Sec-WebSocket-Version")).thenReturn("13");
      when(req.getHeader("Cookie")).thenReturn("kk_ide_session=token");

      HttpServletResponse res = mock(HttpServletResponse.class);

      forwarder.forwardWebSocket(req, res, owner, ENV, VIEW, "/ws");

      verify(res).sendError(404);
    }
  }

  @Test
  void forwardWebSocketMalformedStatusLineReturns502() throws Exception {
    try (AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      int port = ((InetSocketAddress) server.getLocalAddress()).getPort();

      HttpClient httpClient = mock(HttpClient.class);
      IdeClusterClient clusterClient = mock(IdeClusterClient.class);
      when(clusterClient.signHeaders(any(), any(), any())).thenReturn(Map.of());

      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:" + port));

      IdeRemoteDataPlaneForwarder forwarder =
          new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

      Executors.newSingleThreadExecutor()
          .submit(
              () -> {
                try {
                  AsynchronousSocketChannel accepted = server.accept().get();
                  ByteBuffer readBuf = ByteBuffer.allocate(1024);
                  accepted.read(readBuf).get();
                  String response = "HTTP/1.1 NOT_NUMERIC Status\r\n\r\n";
                  accepted
                      .write(ByteBuffer.wrap(response.getBytes(StandardCharsets.ISO_8859_1)))
                      .get();
                } catch (Exception ignored) {
                }
              });

      HttpServletRequest req = mock(HttpServletRequest.class);
      when(req.getContextPath()).thenReturn("");
      when(req.getHeader("Sec-WebSocket-Key")).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
      when(req.getHeader("Sec-WebSocket-Version")).thenReturn("13");
      when(req.getHeader("Cookie")).thenReturn("kk_ide_session=token");

      HttpServletResponse res = mock(HttpServletResponse.class);

      forwarder.forwardWebSocket(req, res, owner, ENV, VIEW, "/ws");

      verify(res).sendError(HttpServletResponse.SC_BAD_GATEWAY);
    }
  }

  @Test
  void forwardHttpErrorHandling() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    IdeClusterClient clusterClient = mock(IdeClusterClient.class);
    ClusterOwnerRoute owner =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:8080"));

    when(clusterClient.signHeaders(any(), any(), any())).thenReturn(Map.of());
    when(httpClient.send(any(), any())).thenThrow(new IOException("network down"));

    IdeRemoteDataPlaneForwarder forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(res.isCommitted()).thenReturn(false);

    forwarder.forwardHttp(req, res, owner, ENV, VIEW, "/test");
    verify(res).sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
  }

  @Test
  void forwardWebSocketConnectFailureSendsBadGateway() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    IdeClusterClient clusterClient = mock(IdeClusterClient.class);
    // Point to non-listening port
    ClusterOwnerRoute owner =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:1"));

    when(clusterClient.signHeaders(any(), any(), any())).thenReturn(Map.of());

    IdeRemoteDataPlaneForwarder forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    when(res.isCommitted()).thenReturn(false);

    forwarder.forwardWebSocket(req, res, owner, ENV, VIEW, "/ws");
    verify(res).sendError(HttpServletResponse.SC_BAD_GATEWAY);
  }

  @Test
  void managementVsDataForwardingPolicies() throws Exception {
    HttpClient httpClient = mock(HttpClient.class);
    IdeClusterClient clusterClient = mock(IdeClusterClient.class);
    ClusterOwnerRoute owner =
        ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:8080"));
    when(clusterClient.signHeaders(any(), any(), any())).thenReturn(Map.of());

    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(303);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of(
                    "Set-Cookie",
                    List.of("kk_ide_session=val; Path=/"),
                    "Location",
                    List.of("/target"),
                    "Service-Worker-Allowed",
                    List.of("/sw")),
                (k, v) -> true));
    when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(httpClient.send(any(), any())).thenReturn((HttpResponse) response);

    IdeRemoteDataPlaneForwarder forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

    // 1. Management forwarding: keeps Set-Cookie and does not rebase Location
    HttpServletRequest reqMgmt = mock(HttpServletRequest.class);
    when(reqMgmt.getMethod()).thenReturn("GET");
    HttpServletResponse resMgmt = mock(HttpServletResponse.class);
    when(resMgmt.getOutputStream())
        .thenReturn(
            new ServletOutputStream() {
              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setWriteListener(WriteListener writeListener) {}

              @Override
              public void write(int b) {}
            });

    forwarder.forwardManagementHttp(reqMgmt, resMgmt, owner, ENV, VIEW, "/__ide/bootstrap");
    verify(resMgmt).addHeader("Set-Cookie", "kk_ide_session=val; Path=/");
    verify(resMgmt).addHeader("Location", "/target");
    verify(resMgmt).addHeader("Service-Worker-Allowed", "/sw");

    // 2. Data forwarding: drops Set-Cookie and rebases Location and Service-Worker-Allowed
    HttpServletRequest reqData = mock(HttpServletRequest.class);
    when(reqData.getMethod()).thenReturn("GET");
    when(reqData.getContextPath()).thenReturn("");
    HttpServletResponse resData = mock(HttpServletResponse.class);
    when(resData.getOutputStream())
        .thenReturn(
            new ServletOutputStream() {
              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setWriteListener(WriteListener writeListener) {}

              @Override
              public void write(int b) {}
            });

    forwarder.forwardDataHttp(reqData, resData, owner, ENV, VIEW, "/page");
    verify(resData, never()).addHeader(eq("Set-Cookie"), any());
    verify(resData).addHeader("Location", "/environment-ide/" + ENV + "/" + VIEW + "/target");
  }

  @Test
  void ideCookieAndBrowserSchemeValidation() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    // null cookies
    when(req.getCookies()).thenReturn(null);
    assertNull(IdeRemoteDataPlaneForwarder.ideCookie(req, "kk_ide_session"));

    // multiple cookies
    Cookie c1 = new Cookie("other", "123");
    Cookie c2 = new Cookie("kk_ide_session", "session-val");
    when(req.getCookies()).thenReturn(new Cookie[] {c1, c2});
    assertEquals(
        "kk_ide_session=session-val", IdeRemoteDataPlaneForwarder.ideCookie(req, "kk_ide_session"));
    assertNull(IdeRemoteDataPlaneForwarder.ideCookie(req, "non-existent"));

    // browserScheme: request.isSecure() -> https
    when(req.isSecure()).thenReturn(true);
    assertEquals("https", IdeRemoteDataPlaneForwarder.browserScheme(req));

    // browserScheme: X-Forwarded-Proto -> https
    when(req.isSecure()).thenReturn(false);
    when(req.getHeader("X-Forwarded-Proto")).thenReturn("http, HTTPS ");
    assertEquals("https", IdeRemoteDataPlaneForwarder.browserScheme(req));

    // browserScheme: default http
    when(req.getHeader("X-Forwarded-Proto")).thenReturn("http");
    assertEquals("http", IdeRemoteDataPlaneForwarder.browserScheme(req));
  }
}
