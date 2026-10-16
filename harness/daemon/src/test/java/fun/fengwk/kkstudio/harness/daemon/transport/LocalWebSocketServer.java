package fun.fengwk.kkstudio.harness.daemon.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地真实 WebSocket 协议 fixture：只接受一条连接，不访问外网，也不解析帧正文。
 *
 * <p>stalled 模式接受 TCP 连接但不返回 HTTP 101，用于验证握手期限有界；upgrading 模式返回带 {@code permessage-deflate} 的
 * 101，用于验证 upgrade 成功后长连接不受握手预算限制。
 *
 * <p>证据区分三类事实：连接是否被接受、对端是否真正结束连接（读 EOF 或建立后的重置）、以及 fixture 自身的 I/O 故障。fixture 主动 {@link #close()}
 * 期间的 accept/read 失败不计为对端关闭；socket 关闭失败与握手阶段的 I/O 故障记入 {@link #failure()}，供测试失败而不是静默丢弃。
 */
final class LocalWebSocketServer implements AutoCloseable {

  private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_REQUEST_BYTES = 16 * 1024;
  private static final long CLOSE_TIMEOUT_MILLIS = 5000;

  private final ServerSocket serverSocket;
  private final boolean upgrade;
  private final CountDownLatch accepted = new CountDownLatch(1);
  private final CountDownLatch upgraded = new CountDownLatch(1);
  private final CountDownLatch receivedFrame = new CountDownLatch(1);
  private final AtomicInteger receivedBytes = new AtomicInteger();
  private final AtomicBoolean peerEnded = new AtomicBoolean();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicReference<IOException> failure = new AtomicReference<>();
  private final Thread thread;
  private volatile Socket client;

  private LocalWebSocketServer(boolean upgrade) throws IOException {
    this.upgrade = upgrade;
    this.serverSocket = new ServerSocket();
    this.serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    this.thread = Thread.ofVirtual().name("local-websocket-server").start(this::serve);
  }

  /** 接受连接但不返回 101。 */
  static LocalWebSocketServer stalled() throws IOException {
    return new LocalWebSocketServer(false);
  }

  /** 返回带 permessage-deflate 的成功 upgrade。 */
  static LocalWebSocketServer upgrading() throws IOException {
    return new LocalWebSocketServer(true);
  }

  URI uri() {
    return URI.create("ws://127.0.0.1:" + serverSocket.getLocalPort() + "/daemon");
  }

  boolean awaitAccepted(long timeoutMillis) throws InterruptedException {
    return accepted.await(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  boolean awaitUpgraded(long timeoutMillis) throws InterruptedException {
    return upgraded.await(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  boolean awaitReceivedFrame(long timeoutMillis) throws InterruptedException {
    return receivedFrame.await(timeoutMillis, TimeUnit.MILLISECONDS);
  }

  /** 等待对端真正结束连接（读 EOF 或建立后的重置）。 */
  boolean awaitPeerEnded(long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    while (System.nanoTime() < deadline && !peerEnded.get()) {
      Thread.sleep(20);
    }
    return peerEnded.get();
  }

  int receivedBytes() {
    return receivedBytes.get();
  }

  /** fixture 自身的意外 I/O 故障；对端正常结束不会记录在此。 */
  IOException failure() {
    return failure.get();
  }

  private void serve() {
    Socket socket;
    try {
      socket = serverSocket.accept();
    } catch (IOException error) {
      if (!closing.get()) {
        recordFailure(error);
      }
      return;
    }
    client = socket;
    accepted.countDown();
    if (closing.get()) {
      // close 先于 accept 返回：收回刚接受的连接，避免服务线程在无人关闭的 socket 上阻塞。
      closeSocket(socket);
      return;
    }
    try {
      Map<String, String> headers = readRequest(socket.getInputStream());
      if (upgrade) {
        OutputStream out = socket.getOutputStream();
        out.write(upgradeResponse(headers.get("sec-websocket-key")));
        out.flush();
        upgraded.countDown();
      }
    } catch (IOException error) {
      // 握手请求/响应阶段的失败是 fixture 协议故障，不得当作对端关闭证据。
      if (!closing.get()) {
        recordFailure(error);
      }
      closeSocket(socket);
      return;
    }
    try {
      readUntilPeerEnds(socket.getInputStream());
    } catch (IOException error) {
      if (!closing.get()) {
        // 连接建立后的读中断（含对端重置）：属于对端结束，而不是 fixture 故障。
        peerEnded.set(true);
      }
    } finally {
      closeSocket(socket);
    }
  }

  /** 读到对端 EOF 即把连接标记为结束；异常由调用方按连接阶段分类。 */
  private void readUntilPeerEnds(InputStream in) throws IOException {
    byte[] buffer = new byte[4096];
    while (true) {
      int read = in.read(buffer);
      if (read < 0) {
        if (!closing.get()) {
          peerEnded.set(true);
        }
        return;
      }
      if (read > 0) {
        receivedBytes.addAndGet(read);
        receivedFrame.countDown();
      }
    }
  }

  private static Map<String, String> readRequest(InputStream in) throws IOException {
    byte[] terminator = {'\r', '\n', '\r', '\n'};
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
    int matched = 0;
    int next;
    while ((next = in.read()) >= 0) {
      headerBytes.write(next);
      matched = next == terminator[matched] ? matched + 1 : (next == terminator[0] ? 1 : 0);
      if (matched == terminator.length) {
        break;
      }
      if (headerBytes.size() > MAX_REQUEST_BYTES) {
        throw new IOException("request header is too large");
      }
    }
    Map<String, String> headers = new HashMap<>();
    for (String line : headerBytes.toString(StandardCharsets.US_ASCII).split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        headers.put(
            line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
            line.substring(colon + 1).trim());
      }
    }
    return headers;
  }

  private static byte[] upgradeResponse(String key) {
    return ("HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + acceptValue(key)
            + "\r\n"
            + "Sec-WebSocket-Extensions: permessage-deflate\r\n"
            + "\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static String acceptValue(String key) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] hash =
          digest.digest(
              ((key == null ? "" : key) + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(hash);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-1 is unavailable", error);
    }
  }

  private void recordFailure(IOException error) {
    failure.compareAndSet(null, error);
  }

  /** 关闭连接；失败记入 fixture failure，而不是被吞掉。 */
  private void closeSocket(Socket socket) {
    try {
      socket.close();
    } catch (IOException error) {
      recordFailure(error);
    }
  }

  @Override
  public void close() throws IOException {
    closing.set(true);
    IOException failure = null;
    try {
      serverSocket.close();
    } catch (IOException error) {
      failure = error;
    }
    Socket socket = client;
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException error) {
        failure = accumulate(failure, error);
      }
    }
    boolean stopped;
    try {
      thread.join(CLOSE_TIMEOUT_MILLIS);
      stopped = !thread.isAlive();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      stopped = !thread.isAlive();
      failure =
          accumulate(failure, new IOException("interrupted while stopping the server", error));
    }
    if (!stopped) {
      failure = accumulate(failure, new IOException("server thread did not stop"));
    }
    // close 结尾核对 fixture 记录的 I/O 故障，避免静默丢弃。
    failure = accumulate(failure, this.failure.get());
    if (failure != null) {
      throw failure;
    }
  }

  private static IOException accumulate(IOException first, IOException next) {
    if (first == null) {
      return next;
    }
    if (first != next) {
      first.addSuppressed(next);
    }
    return first;
  }
}
