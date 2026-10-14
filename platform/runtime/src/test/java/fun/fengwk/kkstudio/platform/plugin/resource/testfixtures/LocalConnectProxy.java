package fun.fengwk.kkstudio.platform.plugin.resource.testfixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实的本地 CONNECT 代理：只按收到的 IP authority 建立隧道，不解析测试媒体域名。
 *
 * <p>不解密 TLS，不改写正文。仅接受 loopback 字面量，避免测试替身意外访问外网；关闭时主动回收所有 socket， 包括 TLS 失败或断言失败时仍阻塞的双向复制。
 */
public final class LocalConnectProxy implements AutoCloseable {

  private final ServerSocket listener;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<Socket> sockets = new HashSet<>();
  private boolean closed;
  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicReference<String> authority = new AtomicReference<>();

  public LocalConnectProxy() throws IOException {
    listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
    workers.submit(
        () -> {
          while (!listener.isClosed()) {
            try {
              Socket client = listener.accept();
              if (!register(client)) {
                return;
              }
              connections.incrementAndGet();
              workers.submit(() -> tunnel(client));
            } catch (IOException error) {
              return;
            }
          }
        });
  }

  public int connections() {
    return connections.get();
  }

  public String authority() {
    return authority.get();
  }

  /** selector 的 socket 主机名是 localhost；生产连接管理器必须用系统 DNS 而非媒体准入解析器。 */
  public ProxySelector selector() {
    return httpSelector(listener.getLocalPort());
  }

  public static ProxySelector httpSelector(int port) {
    Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", port));
    return new ProxySelector() {
      @Override
      public List<Proxy> select(URI uri) {
        return List.of(proxy);
      }

      @Override
      public void connectFailed(URI uri, SocketAddress address, IOException error) {}
    };
  }

  private void tunnel(Socket client) {
    try (client) {
      client.setSoTimeout(10_000);
      InputStream input = client.getInputStream();
      String requestLine = line(input);
      String[] request = requestLine.split(" ");
      if (request.length != 3 || !"CONNECT".equals(request[0])) {
        throw new IOException("expected CONNECT");
      }
      authority.set(request[1]);
      while (!line(input).isEmpty()) {
        // 按字节读头部，不能预读 TLS ClientHello。
      }
      URI target = URI.create("http://" + request[1]);
      if (!"127.0.0.1".equals(target.getHost())) {
        throw new IOException("expected a pinned loopback IP, not a domain");
      }
      try (Socket upstream = new Socket(target.getHost(), target.getPort())) {
        if (!register(upstream)) {
          return;
        }
        client
            .getOutputStream()
            .write(
                "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        workers.submit(() -> copy(client, upstream));
        copy(upstream, client);
      }
    } catch (IOException ignored) {
      // TLS 拒绝和被测客户端主动关闭都会中断隧道。
    }
  }

  private static String line(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) != -1) {
      if (value == '\n') {
        return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();
      }
      bytes.write(value);
      if (bytes.size() > 8192) {
        throw new IOException("proxy header too long");
      }
    }
    throw new IOException("unexpected EOF in proxy header");
  }

  private static void copy(Socket from, Socket to) {
    try {
      from.getInputStream().transferTo(to.getOutputStream());
      to.shutdownOutput();
    } catch (IOException ignored) {
      // 一端关闭后另一方向也由请求/fixture 的 close 回收。
    }
  }

  @Override
  public synchronized void close() throws IOException {
    closed = true;
    listener.close();
    for (Socket socket : sockets) {
      socket.close();
    }
    workers.shutdownNow();
  }

  private synchronized boolean register(Socket socket) throws IOException {
    if (closed) {
      socket.close();
      return false;
    }
    sockets.add(socket);
    return true;
  }
}
