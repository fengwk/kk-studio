package fun.fengwk.kkstudio.ide.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  void readHeaderBlockParsesStatusHeadersAndRemaining() throws IOException {
    String raw =
        "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"
            + "\r\n"
            + "extra-bytes";
    ByteArrayInputStream input =
        new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1));

    IdeRemoteDataPlaneForwarder.HttpResponseHeader head =
        IdeRemoteDataPlaneForwarder.readHeaderBlock(input, 1024);

    assertEquals(101, head.status());
    assertEquals(List.of("websocket"), head.headers().get("Upgrade"));
    assertNotNull(head.remainingBytes());
    assertEquals("extra-bytes", new String(head.remainingBytes(), StandardCharsets.ISO_8859_1));
  }

  @Test
  void readHeaderBlockThrowsWhenExceedingLimit() {
    byte[] huge = new byte[2000];
    ByteArrayInputStream input = new ByteArrayInputStream(huge);
    assertThrows(IOException.class, () -> IdeRemoteDataPlaneForwarder.readHeaderBlock(input, 100));
  }

  @Test
  void forwardHttpStreamsThroughHttpClient() throws Exception {
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

    when(httpClient.send(any(HttpRequest.class), any())).thenReturn((HttpResponse) httpResponse);

    IdeRemoteDataPlaneForwarder forwarder =
        new IdeRemoteDataPlaneForwarder(httpClient, clusterClient);

    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn("GET");
    when(req.getContextPath()).thenReturn("");
    when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
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

    verify(res).setStatus(200);
    verify(res).addHeader("Content-Type", "text/plain");
    assertEquals("response-payload", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void forwardWebSocketRejectionStreamsError() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      int port = server.getLocalPort();
      ClusterOwnerRoute owner =
          ClusterOwnerRoute.remote(UUID.randomUUID(), URI.create("http://127.0.0.1:" + port));

      HttpClient httpClient = mock(HttpClient.class);
      IdeClusterClient clusterClient = mock(IdeClusterClient.class);
      when(clusterClient.signHeaders(any(), any(), any()))
          .thenReturn(Map.of("X-KK-Cluster-Signature", "sig123"));

      IdeRemoteDataPlaneForwarder forwarder =
          new IdeRemoteDataPlaneForwarder(
              httpClient, clusterClient, Executors.newSingleThreadExecutor());

      Executors.newSingleThreadExecutor()
          .submit(
              () -> {
                try (Socket s = server.accept()) {
                  // read until \r\n\r\n
                  byte[] b = new byte[1024];
                  s.getInputStream().read(b);
                  String response =
                      "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nview-not-found";
                  s.getOutputStream().write(response.getBytes(StandardCharsets.ISO_8859_1));
                  s.getOutputStream().flush();
                } catch (Exception ignored) {
                }
              });

      HttpServletRequest req = mock(HttpServletRequest.class);
      when(req.getContextPath()).thenReturn("");
      when(req.getHeader("Sec-WebSocket-Key")).thenReturn("dGhlIHNhbXBsZSBub25jZQ==");
      when(req.getHeader("Sec-WebSocket-Version")).thenReturn("13");
      when(req.getHeader("Cookie")).thenReturn("kk_ide_session=token");

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

      forwarder.forwardWebSocket(req, res, owner, ENV, VIEW, "/ws");

      verify(res).setStatus(404);
      assertEquals("view-not-found", out.toString(StandardCharsets.UTF_8));
    }
  }
}
