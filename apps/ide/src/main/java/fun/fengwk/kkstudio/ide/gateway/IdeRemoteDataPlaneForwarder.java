package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.platform.environment.cluster.ClusterOwnerRoute;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 非 owner 节点的 IDE 数据面转发服务：单跳转发到目标 owner 内部端口。
 *
 * <p>HTTP 请求使用 JDK HttpClient 流式转发，携带 HMAC 签名头； WebSocket 握手使用原始 TCP 发送带 HMAC 头的 HTTP/1.1
 * Upgrade，有界读取响应头后中继字节。
 */
@Component
public class IdeRemoteDataPlaneForwarder {

  private final HttpClient httpClient;
  private final IdeClusterClient clusterClient;
  private final Executor executor;

  public IdeRemoteDataPlaneForwarder(HttpClient httpClient, IdeClusterClient clusterClient) {
    this(httpClient, clusterClient, ForkJoinPool.commonPool());
  }

  public IdeRemoteDataPlaneForwarder(
      HttpClient httpClient, IdeClusterClient clusterClient, Executor executor) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.clusterClient = Objects.requireNonNull(clusterClient, "clusterClient");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  public void forwardHttp(
      HttpServletRequest request,
      HttpServletResponse response,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery)
      throws IOException {
    Objects.requireNonNull(owner, "owner");
    String targetPath = IdeDataPaths.dataHttpPath(environmentId, viewId, rawPathAndQuery);
    URI targetUri = owner.internalUrl().resolve(targetPath);
    Map<String, String> authHeaders =
        clusterClient.signHeaders(request.getMethod(), targetPath, owner);

    boolean hasBody =
        request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    HttpRequest.BodyPublisher bodyPublisher =
        hasBody
            ? HttpRequest.BodyPublishers.ofInputStream(() -> streamOrEmpty(request))
            : HttpRequest.BodyPublishers.noBody();

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(targetUri)
            .method(request.getMethod(), bodyPublisher)
            .timeout(IdeGatewayConstants.DEFAULT_REQUEST_TIMEOUT);

    builder.header(IdeForwardedRequest.SCHEME_HEADER, request.isSecure() ? "https" : "http");
    Map<String, List<String>> forwardHeaders =
        IdeDataHeaderPolicy.forwardRequestHeaders(request, false);
    for (Map.Entry<String, List<String>> entry : forwardHeaders.entrySet()) {
      for (String value : entry.getValue()) {
        builder.header(entry.getKey(), value);
      }
    }
    String cookie = request.getHeader("Cookie");
    if (cookie != null && !cookie.isBlank()) {
      builder.header("Cookie", cookie);
    }
    for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }

    try {
      HttpResponse<InputStream> httpResponse =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      IdeDataHeaderPolicy.applyResponseHeaders(
          httpResponse.statusCode(),
          httpResponse.headers().map(),
          request,
          response,
          environmentId,
          viewId,
          false);
      try (InputStream in = httpResponse.body();
          OutputStream out = response.getOutputStream()) {
        in.transferTo(out);
        out.flush();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
    } catch (Exception error) {
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
    }
  }

  public void forwardWebSocket(
      HttpServletRequest request,
      HttpServletResponse response,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery)
      throws IOException {
    Objects.requireNonNull(owner, "owner");
    String targetPath = IdeDataPaths.dataWebSocketPath(environmentId, viewId, rawPathAndQuery);
    URI uri = owner.internalUrl();
    int port =
        uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    Socket socket = new Socket();
    try {
      socket.connect(
          new InetSocketAddress(uri.getHost(), port),
          (int) IdeGatewayConstants.DEFAULT_CONNECT_TIMEOUT.toMillis());
      Map<String, String> authHeaders = clusterClient.signHeaders("GET", targetPath, owner);

      StringBuilder req = new StringBuilder();
      req.append("GET ").append(targetPath).append(" HTTP/1.1\r\n");
      req.append("Host: ").append(uri.getHost()).append(":").append(port).append("\r\n");
      req.append("Connection: Upgrade\r\n");
      req.append("Upgrade: websocket\r\n");
      addHeaderIfPresent(req, request, "Sec-WebSocket-Key");
      addHeaderIfPresent(req, request, "Sec-WebSocket-Version");
      addHeaderIfPresent(req, request, "Sec-WebSocket-Protocol");
      addHeaderIfPresent(req, request, "Sec-WebSocket-Extensions");
      addHeaderIfPresent(req, request, "Cookie");
      req.append(IdeForwardedRequest.SCHEME_HEADER)
          .append(": ")
          .append(request.isSecure() ? "https" : "http")
          .append("\r\n");
      for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
        req.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
      req.append("\r\n");

      socket.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
      socket.getOutputStream().flush();

      HttpResponseHeader head =
          readHeaderBlock(socket.getInputStream(), IdeGatewayConstants.MAX_HEADER_BYTES);
      if (head.status == 101) {
        TcpTunnelStream tunnelStream = new TcpTunnelStream(socket, executor, head.remainingBytes);
        JettyConnectionHandover.handover(request, 101, head.headers, tunnelStream);
      } else {
        IdeDataHeaderPolicy.applyResponseHeaders(
            head.status, head.headers, request, response, environmentId, viewId, false);
        try (OutputStream out = response.getOutputStream()) {
          if (head.remainingBytes != null && head.remainingBytes.length > 0) {
            out.write(head.remainingBytes);
          }
          socket.getInputStream().transferTo(out);
          out.flush();
        } finally {
          socket.close();
        }
      }
    } catch (Exception error) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
    }
  }

  private static InputStream streamOrEmpty(HttpServletRequest request) {
    try {
      return request.getInputStream();
    } catch (IOException error) {
      return InputStream.nullInputStream();
    }
  }

  private static void addHeaderIfPresent(
      StringBuilder req, HttpServletRequest request, String headerName) {
    String value = request.getHeader(headerName);
    if (value != null && !value.isBlank()) {
      req.append(headerName).append(": ").append(value).append("\r\n");
    }
  }

  public record HttpResponseHeader(
      int status, Map<String, List<String>> headers, byte[] remainingBytes) {}

  public static HttpResponseHeader readHeaderBlock(InputStream input, int maxBytes)
      throws IOException {
    byte[] buffer = new byte[Math.min(maxBytes, 8192)];
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
    int totalRead = 0;

    while (totalRead < maxBytes) {
      int read = input.read(buffer);
      if (read < 0) {
        break;
      }
      headerBytes.write(buffer, 0, read);
      totalRead += read;
      byte[] current = headerBytes.toByteArray();
      int headerEndIndex = findHeaderEnd(current);
      if (headerEndIndex >= 0) {
        int remainingLength = current.length - headerEndIndex;
        byte[] overflow = new byte[remainingLength];
        System.arraycopy(current, headerEndIndex, overflow, 0, remainingLength);
        String headerString = new String(current, 0, headerEndIndex, StandardCharsets.ISO_8859_1);
        return parseHeaderString(headerString, overflow);
      }
    }
    throw new IOException("HTTP response header exceeded limit or stream closed prematurely");
  }

  private static int findHeaderEnd(byte[] bytes) {
    for (int i = 0; i < bytes.length - 3; i++) {
      if (bytes[i] == '\r'
          && bytes[i + 1] == '\n'
          && bytes[i + 2] == '\r'
          && bytes[i + 3] == '\n') {
        return i + 4;
      }
    }
    return -1;
  }

  private static HttpResponseHeader parseHeaderString(String block, byte[] overflow) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    String[] lines = block.split("\r\n");
    int status = -1;
    if (lines.length > 0) {
      String statusLine = lines[0];
      String[] parts = statusLine.split(" ", 3);
      if (parts.length >= 2) {
        try {
          status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
        }
      }
    }
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      int colon = line.indexOf(':');
      if (colon > 0) {
        String name = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
      }
    }
    return new HttpResponseHeader(status, headers, overflow);
  }
}
