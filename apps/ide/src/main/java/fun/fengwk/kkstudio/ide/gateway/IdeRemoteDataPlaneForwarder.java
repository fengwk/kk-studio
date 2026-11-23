package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeClusterClient;
import fun.fengwk.kkstudio.platform.cluster.ClusterOwnerRoute;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 非 owner 节点的 IDE 数据面转发服务：单跳转发到目标 owner 内部端口。
 *
 * <p>HTTP 请求使用 JDK HttpClient 流式转发，携带 HMAC 签名头； WebSocket 握手通过节点间连接发送带 HMAC 头的 HTTP/1.1
 * Upgrade，有界读取响应头后中继字节。
 */
@Slf4j
@Component
public class IdeRemoteDataPlaneForwarder {

  private final HttpClient httpClient;
  private final IdeClusterClient clusterClient;

  public IdeRemoteDataPlaneForwarder(
      @Qualifier("clusterInternalHttpClient") HttpClient httpClient,
      IdeClusterClient clusterClient) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.clusterClient = Objects.requireNonNull(clusterClient, "clusterClient");
  }

  public void forwardDataHttp(
      HttpServletRequest request,
      HttpServletResponse response,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery)
      throws IOException {
    forwardHttp(request, response, owner, environmentId, viewId, rawPathAndQuery, false);
  }

  public void forwardManagementHttp(
      HttpServletRequest request,
      HttpServletResponse response,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery)
      throws IOException {
    forwardHttp(request, response, owner, environmentId, viewId, rawPathAndQuery, true);
  }

  private void forwardHttp(
      HttpServletRequest request,
      HttpServletResponse response,
      ClusterOwnerRoute owner,
      EnvironmentId environmentId,
      UUID viewId,
      String rawPathAndQuery,
      boolean management)
      throws IOException {
    Objects.requireNonNull(owner, "owner");
    String targetPath = IdeDataPaths.dataHttpPath(environmentId, viewId, rawPathAndQuery);
    URI targetUri = owner.internalUrl().resolve(targetPath);
    Map<String, String> authHeaders =
        clusterClient.signHeaders(request.getMethod(), targetPath, owner);

    boolean hasBody =
        request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    HttpRequest.BodyPublisher bodyPublisher;
    if (hasBody) {
      InputStream bodyStream = request.getInputStream();
      bodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> bodyStream);
    } else {
      bodyPublisher = HttpRequest.BodyPublishers.noBody();
    }

    HttpRequest.Builder builder =
        HttpRequest.newBuilder(targetUri)
            .method(request.getMethod(), bodyPublisher)
            .timeout(IdeGatewayConstants.DEFAULT_REQUEST_TIMEOUT);

    builder.header(IdeForwardedRequest.SCHEME_HEADER, browserScheme(request));
    Map<String, List<String>> forwardHeaders =
        IdeDataHeaderPolicy.forwardRequestHeaders(request, false);
    for (Map.Entry<String, List<String>> entry : forwardHeaders.entrySet()) {
      for (String value : entry.getValue()) {
        builder.header(entry.getKey(), value);
      }
    }
    String cookie = ideCookie(request, IdeGatewayConstants.SESSION_COOKIE_NAME);
    if (cookie != null && !cookie.isBlank()) {
      builder.header("Cookie", cookie);
    }
    for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
      builder.header(entry.getKey(), entry.getValue());
    }

    try {
      HttpResponse<InputStream> httpResponse =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      copyResponse(httpResponse, response, request, environmentId, viewId, management);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      log.info(
          "remote ide data plane forward failed; phase=IDE_REMOTE_FORWARD_INTERRUPTED; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      }
    } catch (Exception error) {
      log.info(
          "remote ide data plane forward failed; phase=IDE_REMOTE_FORWARD; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      }
    }
  }

  private static void copyResponse(
      HttpResponse<InputStream> httpResponse,
      HttpServletResponse response,
      HttpServletRequest request,
      EnvironmentId environmentId,
      UUID viewId,
      boolean management)
      throws IOException {
    response.setStatus(httpResponse.statusCode());
    for (Map.Entry<String, List<String>> header : httpResponse.headers().map().entrySet()) {
      String name = header.getKey();
      if (!IdeDataHeaderPolicy.isValidHeaderName(name)) {
        continue;
      }
      String lower = name.toLowerCase(Locale.ROOT);
      if (lower.equals("content-length")
          || IdeDataHeaderPolicy.isHopByHop(lower)
          || (!management && "set-cookie".equals(lower))) {
        continue;
      }
      for (String value : header.getValue()) {
        if (!IdeDataHeaderPolicy.isValidHeaderValue(value)) {
          continue;
        }
        if (!management && "location".equals(lower)) {
          value = IdeDataPaths.rebaseLocation(value, request, environmentId, viewId);
        }
        if (!management && "service-worker-allowed".equals(lower)) {
          value = IdeDataPaths.rebaseWorkerScope(value, request, environmentId, viewId);
        }
        response.addHeader(name, value);
      }
    }
    try (InputStream in = httpResponse.body();
        OutputStream out = response.getOutputStream()) {
      in.transferTo(out);
      out.flush();
    }
  }

  static String ideCookie(HttpServletRequest request, String cookieName) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (cookieName.equals(cookie.getName())) {
        return cookieName + "=" + cookie.getValue();
      }
    }
    return null;
  }

  static String browserScheme(HttpServletRequest request) {
    if (request.isSecure()) {
      return "https";
    }
    String forwardedProto = request.getHeader("X-Forwarded-Proto");
    if (forwardedProto != null) {
      for (String value : forwardedProto.split(",")) {
        if (value.trim().equalsIgnoreCase("https")) {
          return "https";
        }
      }
    }
    return "http";
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
    boolean secure = "https".equalsIgnoreCase(uri.getScheme());
    String host = uri.getHost();
    int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
    int connectTimeout = Math.toIntExact(IdeGatewayConstants.DEFAULT_CONNECT_TIMEOUT.toMillis());

    Socket transport = new Socket();
    Socket socket = transport;
    boolean handedOver = false;
    try {
      transport.connect(new InetSocketAddress(host, port), connectTimeout);
      if (secure) {
        SSLSocket tls =
            (SSLSocket)
                httpClient
                    .sslContext()
                    .getSocketFactory()
                    .createSocket(transport, host, port, true);
        socket = tls;
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        // SNI names are DNS names, not IP literals.
        if (host.indexOf(':') < 0 && !host.matches("[0-9.]+")) {
          parameters.setServerNames(List.of(new SNIHostName(host)));
        }
        tls.setSSLParameters(parameters);
        runWithTimeout(
            () -> {
              tls.startHandshake();
              return null;
            },
            connectTimeout);
      }

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
      String cookie = ideCookie(request, IdeGatewayConstants.SESSION_COOKIE_NAME);
      if (cookie != null) {
        req.append("Cookie: ").append(cookie).append("\r\n");
      }
      req.append(IdeForwardedRequest.SCHEME_HEADER)
          .append(": ")
          .append(browserScheme(request))
          .append("\r\n");
      for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
        req.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
      req.append("\r\n");

      OutputStream output = socket.getOutputStream();
      byte[] requestBytes = req.toString().getBytes(StandardCharsets.ISO_8859_1);
      runWithTimeout(
          () -> {
            output.write(requestBytes);
            return null;
          },
          IdeGatewayConstants.DEFAULT_REQUEST_TIMEOUT.toMillis());

      socket.setSoTimeout(connectTimeout);
      HttpResponseHeader head =
          readHeaderBlock(socket.getInputStream(), IdeGatewayConstants.MAX_HEADER_BYTES);

      if (head.status == 101) {
        socket.setSoTimeout(0);
        TcpTunnelStream tunnelStream = new TcpTunnelStream(socket, transport, head.remainingBytes);
        JettyConnectionHandover.handover(request, 101, head.headers, tunnelStream);
        handedOver = true;
      } else {
        IdeDataHeaderPolicy.sendError(response, head.status);
      }
    } catch (Exception error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.info(
          "remote ide data plane forward failed; phase=IDE_REMOTE_WS_FORWARD; environmentId={}; viewId={}; exception={}",
          environmentId,
          viewId,
          error.getClass().getSimpleName());
      if (!response.isCommitted()) {
        IdeDataHeaderPolicy.sendError(response, HttpServletResponse.SC_BAD_GATEWAY);
      }
    } finally {
      if (!handedOver) {
        closeSocket(transport);
        if (socket != transport) {
          closeSocket(socket);
        }
      }
    }
  }

  // Socket SO_TIMEOUT only bounds reads. Use a separate task for writes and TLS handshake;
  // on failure the caller closes the raw transport to abort any pending I/O.
  private static void runWithTimeout(Callable<Void> action, long timeoutMillis) throws Exception {
    FutureTask<Void> task = new FutureTask<>(action);
    Thread.startVirtualThread(task);
    try {
      task.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } finally {
      task.cancel(true);
    }
  }

  private static void closeSocket(Socket socket) {
    try {
      socket.close();
    } catch (IOException error) {
      log.debug("failed to close socket after forward error", error);
    }
  }

  private static void addHeaderIfPresent(
      StringBuilder req, HttpServletRequest request, String headerName) {
    String value = request.getHeader(headerName);
    if (value != null && !value.isBlank()) {
      req.append(headerName).append(": ").append(value).append("\r\n");
    }
  }

  record HttpResponseHeader(int status, Map<String, List<String>> headers, byte[] remainingBytes) {}

  static HttpResponseHeader readHeaderBlock(InputStream input, int maxBytes) throws IOException {
    byte[] buffer = new byte[Math.min(maxBytes, 8192)];
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();

    while (headerBytes.size() < maxBytes) {
      int read = input.read(buffer, 0, Math.min(buffer.length, maxBytes - headerBytes.size()));
      if (read < 0) {
        break;
      }
      headerBytes.write(buffer, 0, read);

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

  private static HttpResponseHeader parseHeaderString(String block, byte[] overflow)
      throws IOException {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    String[] lines = block.split("\r\n");
    if (lines.length == 0) {
      throw new IOException("malformed status line");
    }
    String statusLine = lines[0];
    String[] parts = statusLine.split(" ", 3);
    if (parts.length < 2) {
      throw new IOException("malformed status line");
    }
    int status;
    try {
      status = Integer.parseInt(parts[1]);
    } catch (NumberFormatException error) {
      throw new IOException("malformed status line", error);
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
