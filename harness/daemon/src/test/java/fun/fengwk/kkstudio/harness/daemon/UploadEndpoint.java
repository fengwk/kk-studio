package fun.fengwk.kkstudio.harness.daemon;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用预签名 PUT 目标：一个只接受精确方法、signed header 与声明 checksum 的本地 HTTP 端点。
 *
 * <p>只表达 Daemon 直传真正依赖的事实：请求方法、{@code If-None-Match} 与请求体字节；不模拟 S3 签名。因此测试可以断言 Daemon 发出的 PUT
 * 与票据完全一致，并统计上传次数，用于证明终态重放不会重复上传。
 */
final class UploadEndpoint implements AutoCloseable {

  private final HttpServer server;
  private final AtomicInteger uploads = new AtomicInteger();
  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
  private volatile String expectedSha256;
  private volatile long responseDelayMillis;

  private UploadEndpoint(HttpServer server) {
    this.server = server;
  }

  /** 启动一个仅绑定回环地址、随机端口的端点。 */
  static UploadEndpoint start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    UploadEndpoint endpoint = new UploadEndpoint(server);
    server.createContext(
        "/upload",
        exchange -> {
          endpoint.lastMethod.set(exchange.getRequestMethod());
          ByteArrayOutputStream body = new ByteArrayOutputStream();
          try (InputStream in = exchange.getRequestBody()) {
            in.transferTo(body);
          }
          byte[] bytes = body.toByteArray();
          endpoint.lastBody.set(bytes);
          // 签名固定的 create-only PUT：带 If-None-Match: * 且 checksum 与声明一致才接受。
          boolean createOnly = "*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"));
          boolean checksumMatches =
              endpoint.expectedSha256 != null
                  && endpoint.expectedSha256.equals(endpoint.sha256Hex(bytes));
          if (!"PUT".equals(exchange.getRequestMethod()) || !createOnly || !checksumMatches) {
            exchange.sendResponseHeaders(412, -1);
            exchange.close();
            return;
          }
          if (endpoint.responseDelayMillis > 0) {
            try {
              Thread.sleep(endpoint.responseDelayMillis);
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
              exchange.close();
              return;
            }
          }
          endpoint.uploads.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    return endpoint;
  }

  String putUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/upload";
  }

  /** 声明本次上传期望的对象摘要；与请求体不符则以 412 拒绝，模拟对象存储的 checksum 校验收敛。 */
  void expectUpload(String sha256) {
    this.expectedSha256 = sha256;
  }

  /** 延迟 PUT 响应，用于验证客户端总调用时长受 invocation deadline 约束。 */
  void delayResponse(long millis) {
    this.responseDelayMillis = millis;
  }

  int uploadCount() {
    return uploads.get();
  }

  String lastMethod() {
    return lastMethod.get();
  }

  byte[] lastBody() {
    return lastBody.get() == null ? new byte[0] : lastBody.get();
  }

  String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }

  void assertNoSensitiveLeakIn(Throwable error) {
    String text = String.valueOf(error);
    Assertions.assertFalse(text.contains(putUrl()), "presigned URL leaked into " + text);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
